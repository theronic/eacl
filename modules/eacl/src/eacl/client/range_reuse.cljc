(ns eacl.client.range-reuse
  "Serving a shorter page as a prefix (or suffix) of a longer completed page.

  Both public orders are deterministic functions of plan, snapshot, and
  start boundary, so the first `M` results of a completed `:first N` page
  from one start boundary are the `:first M` page, and the last `M` results
  of a completed `:last N` page are the `:last M` page. A route that marks
  its internal page `:range-reusable?` retains one internal edge per result
  (`:edges`), from which the derived page's boundary cursor is read. Bounded
  candidate-window routes carry no marker and never participate.

  The range tier is a client-owned bounded store keyed by the exact basis and
  the semantic key minus page size; it keeps the longest completed page per
  key. Derived pages are returned to the ordinary path, which renders and
  publishes them under their exact keys like computed pages."
  (:require [eacl.cache.standard-lru :as lru]))

(def default-max-entries 512)

(defn tier
  [{:keys [max-entries] :or {max-entries default-max-entries}}]
  {:store (lru/store max-entries)
   :max-entries max-entries
   :metrics (atom {:hits 0 :misses 0 :deposits 0 :supersessions 0})})

(defn tier?
  [value]
  (and (map? value) (lru/store? (:store value))))

(defn- meter!
  [tier metric]
  (when tier (swap! (:metrics tier) update metric inc))
  nil)

(defn stats
  [tier]
  (assoc @(:metrics tier)
         :entry-count (lru/entry-count (:store tier))
         :max-entries (:max-entries tier)))

(defn page-size
  "The requested page size and window kind of a page query:
  `[:first n]`, `[:last n]`, or nil when the query names neither."
  [query]
  (cond
    (contains? query :first) [:first (:first query)]
    (contains? query :last) [:last (:last query)]
    :else nil))

(defn range-key
  "The reuse identity of a page semantic key: the key minus page size, plus
  the window kind. Nil when the query is not a sized page."
  [exact-basis-key semantic-key]
  (let [query (:query semantic-key)
        public (:public query)]
    (when (and (map? query) (map? public))
      (when-let [[kind _] (page-size public)]
        (let [strip (fn [m] (if (map? m) (dissoc m :first :last) m))
              ;; The execution demand names the page size too; every other
              ;; demand dimension (kind, direction, window bounding) stays.
              unsize (fn [demand] (if (map? demand) (dissoc demand :size) demand))]
          [:range exact-basis-key kind
           (-> semantic-key
               (assoc :query (-> query
                                 (update :public strip)
                                 (update :internal strip)))
               (update :demand unsize))])))))

(defn reusable-page?
  "True for a completed internal page a route marked reusable, with one
  edge per result."
  [page]
  (and (map? page)
       (true? (:range-reusable? page))
       (vector? (:data page))
       (vector? (:edges page))
       (= (count (:data page)) (count (:edges page)))
       (map? (:page-info page))))

(defn derive-page
  "The `[kind requested]` page derived from a resident completed page of the
  same range, or nil when the resident page cannot answer it exactly.

  `:first M`: the first `M` results when the resident page holds at least
  `M`; the whole resident page when it holds fewer and has no next page.
  `:last M`: symmetric on the suffix and the previous-page flag."
  [[kind requested] resident]
  (when (and (reusable-page? resident) (pos-int? requested))
    (let [data (:data resident)
          edges (:edges resident)
          info (:page-info resident)
          held (count data)]
      (case kind
        :first
        (cond
          (<= requested held)
          (let [info' (assoc info
                             :end-cursor (nth edges (dec requested))
                             :has-next-page? (boolean
                                              (or (> held requested)
                                                  (:has-next-page? info))))]
            (if (= requested held)
              resident
              (assoc resident
                     :data (subvec data 0 requested)
                     :edges (subvec edges 0 requested)
                     :page-info info')))
          (not (:has-next-page? info)) resident
          :else nil)

        :last
        (cond
          (<= requested held)
          (let [start (- held requested)
                info' (assoc info
                             :start-cursor (nth edges start)
                             :has-previous-page? (boolean
                                                  (or (pos? start)
                                                      (:has-previous-page?
                                                       info))))]
            (if (zero? start)
              resident
              (assoc resident
                     :data (subvec data start)
                     :edges (subvec edges start)
                     :page-info info')))
          (not (:has-previous-page? info)) resident
          :else nil)

        nil))))

(defn lookup!
  "A derived page for `page-size` from the tier's resident page under
  `key`, or nil."
  [tier key page-size]
  (when (and tier key page-size)
    (let [hit (lru/lookup! (:store tier) key)]
      (if-let [derived (and (:found? hit)
                            (derive-page page-size (:value hit)))]
        (do (meter! tier :hits) derived)
        (do (meter! tier :misses) nil)))))

(defn publish!
  "Retains `page` under `key` when it is reusable and longer than the
  resident page (or no page is resident)."
  [tier key page]
  (when (and tier key (reusable-page? page))
    (let [store (:store tier)]
      (loop []
        (let [hit (lru/lookup! store key)
              resident (when (:found? hit) (:value hit))]
          (cond
            (nil? resident)
            (if (lru/put-if-absent! store key page)
              (meter! tier :deposits)
              (recur))

            (> (count (:data page)) (count (:data resident)))
            (if (lru/replace-if! store key resident page)
              (meter! tier :supersessions)
              (recur))

            :else nil))))))
