(ns eacl.client.range-reuse
  "Serving any page window of a walk from retained completed segments.

  Both public orders are deterministic functions of plan, snapshot, and
  boundary, so every completed plain page is a contiguous segment of one
  fixed result sequence, and the internal edge retained per result names
  every boundary inside it. The tier is keyed by the walk (exact basis plus
  the semantic key minus page size and boundary, plus the window kind) and
  holds a bounded list of segments per walk. A window whose boundary is a
  segment's start boundary or one of its edges is served from the segment
  when the segment holds the whole window or the rest of the walk in that
  direction; a window that runs past a segment with a next page yields the
  segment's tail plus a continuation request for the remainder, which the
  orchestration composes. Publication merges a page that continues a
  segment into it, so ordinary paging accumulates one segment.

  Derived and composed pages are returned to the ordinary path, which
  renders and publishes them under their exact keys like computed pages.
  Bounded candidate-window routes carry no marker and never participate."
  (:require [eacl.cache.standard-lru :as lru]))

(def default-max-entries 512)
(def default-max-results-per-walk 4096)
(def default-max-segments-per-walk 8)

(def ^:dynamic ^:no-doc *disabled?*
  "Internal test and benchmark seam: when true no page is derived,
  composed, or published through the range tier."
  false)

(defn tier
  [{:keys [max-entries max-results-per-walk max-segments-per-walk]
    :or {max-entries default-max-entries
         max-results-per-walk default-max-results-per-walk
         max-segments-per-walk default-max-segments-per-walk}}]
  {:store (lru/store max-entries)
   :max-entries max-entries
   :max-results-per-walk max-results-per-walk
   :max-segments-per-walk max-segments-per-walk
   :metrics (atom {:hits 0 :partial-hits 0 :resumes 0 :misses 0
                   :deposits 0 :extensions 0 :supersessions 0})})

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

;; ---------------------------------------------------------------------------
;; Keys and windows
;; ---------------------------------------------------------------------------

(defn page-size
  "The requested page size and window kind of a page query:
  `[:first n]`, `[:last n]`, or nil when the query names neither."
  [query]
  (cond
    (contains? query :first) [:first (:first query)]
    (contains? query :last) [:last (:last query)]
    :else nil))

(defn- unsized
  [m]
  (if (map? m) (dissoc m :first :last :after :before) m))

(defn walk-key
  "The reuse identity of a page semantic key: the key minus page size and
  boundary, plus the window kind. Nil when the query is not a sized page."
  [exact-basis-key semantic-key]
  (let [query (:query semantic-key)
        public (:public query)]
    (when (and (map? query) (map? public))
      (when-let [[kind _] (page-size public)]
        (let [;; The execution demand names the page size too; every other
              ;; demand dimension (kind, direction, window bounding) stays.
              undemand (fn [demand] (if (map? demand) (dissoc demand :size) demand))]
          [:walk exact-basis-key kind
           (-> semantic-key
               (assoc :query (-> query
                                 (update :public unsized)
                                 (update :internal unsized)))
               (update :demand undemand))])))))

(def ^:deprecated range-key
  "Former name of `walk-key`."
  walk-key)

(defn window
  "The requested window of a page semantic key: `{:kind :first :size n
  :boundary edge-or-nil}` with the authenticated internal boundary edge.
  Nil for an unsized query and for every page request the engine would
  reject (both sizes, both or mismatched boundaries, a present nil
  boundary, a non-positive or oversized size, retired pagination keys), so
  a served window never pre-empts the engine's own validation."
  ([semantic-key] (window semantic-key nil))
  ([semantic-key max-size]
   (let [query (:query semantic-key)
         internal (or (:internal query) (:public query))]
     (when (and (map? internal)
                (not (contains? internal :cursor))
                (not (contains? internal :limit))
                (not (and (contains? internal :first) (contains? internal :last))))
       (when-let [[kind size] (page-size internal)]
         (let [[own other] (case kind :first [:after :before] :last [:before :after])]
           (when (and (pos-int? size)
                      (or (nil? max-size) (<= size max-size))
                      (not (contains? internal other))
                      (or (not (contains? internal own))
                          (some? (get internal own))))
             {:kind kind
              :size size
              :boundary (get internal own)})))))))

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

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

(defn- edge-key
  "Edges compare structurally; a filtered route's inclusive-resume marker
  is not part of the boundary identity."
  [edge]
  (when (map? edge) (dissoc edge :resume-inclusive?)))

(defn- index-edges
  [edges]
  (persistent!
   (reduce (fn [m i] (assoc! m (edge-key (nth edges i)) i))
           (transient {})
           (range (count edges)))))

(defn- segment-of
  "A segment from a computed page and the window that produced it. The
  page's data and edges are in canonical forward order for both kinds. The
  window's size names the page series whose checkpoint sits at the
  segment's end."
  [{:keys [kind boundary size]} page]
  (let [info (:page-info page)
        edges (:edges page)]
    {:data (:data page)
     :edges edges
     :index (index-edges edges)
     :series-size size
     :start-boundary (when (= :first kind) (edge-key boundary))
     :end-boundary (when (= :last kind) (edge-key boundary))
     :has-previous? (boolean (:has-previous-page? info))
     :has-next? (boolean (:has-next-page? info))}))

(defn- slice-page
  "The internal page for results `[from, to)` of `segment`."
  [segment from to]
  (let [data (subvec (:data segment) from to)
        edges (subvec (:edges segment) from to)
        n (count (:data segment))]
    {:data data
     :edges edges
     :range-reusable? true
     :page-info {:start-cursor (when (seq edges) (nth edges 0))
                 :end-cursor (when (seq edges) (peek edges))
                 :has-next-page? (boolean (and (seq data)
                                               (or (< to n) (:has-next? segment))))
                 :has-previous-page? (boolean (and (seq data)
                                                   (or (pos? from)
                                                       (:has-previous? segment))))}}))

(defn ^:no-doc forward-position
  "The index in `segment` of the first result after `boundary`, or nil
  when the boundary is not one of the segment's boundaries."
  [segment boundary]
  (let [key (edge-key boundary)]
    (cond
      (nil? key) (when-not (:has-previous? segment) 0)
      (= key (:start-boundary segment)) 0
      :else (some-> (get (:index segment) key) inc))))

(defn ^:no-doc backward-position
  "The index in `segment` one past the last result before `boundary`, or
  nil when the boundary is not one of the segment's boundaries."
  [segment boundary]
  (let [key (edge-key boundary)
        n (count (:data segment))]
    (cond
      (nil? key) (when-not (:has-next? segment) n)
      (= key (:end-boundary segment)) n
      :else (get (:index segment) key))))

(defn ^:no-doc serve-from-segment
  "`{:page p}` when the segment answers the window exactly, `{:partial p
  :continuation c}` when the window runs past a segment that continues,
  `{:continuation c}` when the window starts exactly at the segment's end
  (the continuation names the series whose checkpoint sits there), or
  nil."
  [{:keys [kind size boundary]} segment]
  (let [n (count (:data segment))]
    (case kind
      :first
      (when-let [from (forward-position segment boundary)]
        (let [held (- n from)
              continuation (fn [remaining]
                             (cond-> {:kind :first
                                      :size remaining
                                      :boundary (peek (:edges segment))}
                               (:series-size segment)
                               (assoc :checkpoint-size (:series-size segment))))]
          (cond
            (>= held size) {:page (slice-page segment from (+ from size))}
            (not (:has-next? segment)) {:page (slice-page segment from n)}
            (pos? held) {:partial (slice-page segment from n)
                         :continuation (continuation (- size held))}
            (and (pos? n) (:series-size segment)) {:continuation (continuation size)}
            :else nil)))
      :last
      (when-let [to (backward-position segment boundary)]
        (cond
          (>= to size) {:page (slice-page segment (- to size) to)}
          (not (:has-previous? segment)) {:page (slice-page segment 0 to)}
          (pos? to) {:partial (slice-page segment 0 to)
                     :continuation {:kind :last
                                    :size (- size to)
                                    :boundary (nth (:edges segment) 0)}}
          :else nil))
      nil)))

(defn- best
  [candidates]
  (or (some #(when (:page %) %) candidates)
      (first (sort-by #(- (count (:data (:partial %)))) (filter :partial candidates)))
      (some #(when (:continuation %) %) candidates)))

(defn derive-window
  "The complete or partial answer for `window` from `segments`, or nil."
  [window segments]
  (best (keep #(serve-from-segment window %) segments)))

(defn derive-page
  "The `[kind requested]` page derived from a resident completed page of the
  same start boundary, or nil when the resident page cannot answer it
  exactly: the first (or, for `:last`, the final) `requested` results when
  the resident page holds at least that many; the whole resident page when
  it holds fewer and the walk ends there."
  [[kind requested] resident]
  (when (and (reusable-page? resident) (pos-int? requested))
    (let [segment (segment-of {:kind kind :boundary nil} resident)
          n (count (:data segment))]
      (case kind
        :first (cond
                 (>= n requested) (slice-page segment 0 requested)
                 (not (:has-next? segment)) (slice-page segment 0 n)
                 :else nil)
        :last (cond
                (>= n requested) (slice-page segment (- n requested) n)
                (not (:has-previous? segment)) (slice-page segment 0 n)
                :else nil)
        nil))))

(defn compose
  "The page for a window answered by `partial` (a segment's tail or head)
  plus `remainder`, the ordinary continuation the orchestration ran for
  `continuation`; nil when the remainder is not a reusable page, in which
  case the caller computes the whole window."
  [partial {:keys [kind]} remainder]
  (when (and (reusable-page? partial) (reusable-page? remainder))
    (let [[left right] (case kind
                         :first [partial remainder]
                         :last [remainder partial])
          data (into (:data left) (:data right))
          edges (into (:edges left) (:edges right))
          left-info (:page-info left)
          right-info (:page-info right)]
      {:data data
       :edges edges
       :range-reusable? true
       :page-info {:start-cursor (when (seq edges) (nth edges 0))
                   :end-cursor (when (seq edges) (peek edges))
                   :has-next-page? (boolean
                                    (if (seq (:data right))
                                      (:has-next-page? right-info)
                                      (:has-next-page? left-info)))
                   :has-previous-page? (boolean
                                        (if (seq (:data left))
                                          (:has-previous-page? left-info)
                                          (:has-previous-page? right-info)))}})))

;; ---------------------------------------------------------------------------
;; Publication: merging and bounds
;; ---------------------------------------------------------------------------

(defn- concat-segments
  "The series at the merged segment's end is the right part's series."
  [left right]
  (let [edges (into (:edges left) (:edges right))]
    {:data (into (:data left) (:data right))
     :edges edges
     :index (index-edges edges)
     :series-size (:series-size right)
     :start-boundary (:start-boundary left)
     :end-boundary (:end-boundary right)
     :has-previous? (:has-previous? left)
     :has-next? (:has-next? right)}))

(defn- covers?
  "True when `segment` already holds every result of `candidate` at the
  same boundaries (a derived page republished, or a repeat)."
  [segment candidate]
  (let [from (if-let [b (:start-boundary candidate)]
               (some-> (get (:index segment) b) inc)
               (when (and (nil? (:start-boundary candidate))
                          (or (not (:has-previous? candidate))
                              (:end-boundary candidate)))
                 (if (:end-boundary candidate)
                   (when-let [to (get (:index segment) (:end-boundary candidate))]
                     (- to (count (:data candidate))))
                   0)))]
    (and from
         (>= from 0)
         (<= (+ from (count (:data candidate))) (count (:data segment)))
         (= (:edges candidate)
            (subvec (:edges segment) from (+ from (count (:data candidate))))))))

(defn- merge-segment
  "Merges `candidate` into `segments`: covered by a segment → unchanged;
  continues a segment's end (its start boundary is that segment's last
  edge) → appended; ends at a segment's start boundary (its last edge is
  that segment's start boundary, or the segment's first edge is the
  candidate's end boundary) → prepended; otherwise a new segment."
  [segments candidate]
  (let [n (count segments)]
    (loop [i 0]
      (if (= i n)
        [(conj segments candidate) :deposit]
        (let [segment (nth segments i)]
          (cond
            (covers? segment candidate)
            [segments :covered]

            (and (seq (:edges segment))
                 (:start-boundary candidate)
                 (= (:start-boundary candidate) (edge-key (peek (:edges segment)))))
            [(assoc segments i (concat-segments segment candidate)) :extension]

            (and (seq (:edges candidate))
                 (seq (:edges segment))
                 (or (and (:start-boundary segment)
                          (= (:start-boundary segment) (edge-key (peek (:edges candidate)))))
                     (and (:end-boundary candidate)
                          (= (:end-boundary candidate) (edge-key (nth (:edges segment) 0))))))
            [(assoc segments i (concat-segments candidate segment)) :extension]

            :else (recur (inc i))))))))

(defn- bound-segments
  "Drops the oldest segments while the walk exceeds its caps."
  [segments max-results max-segments]
  (loop [segments segments]
    (let [total (reduce + 0 (map #(count (:data %)) segments))]
      (if (and (> (count segments) 1)
               (or (> total max-results)
                   (> (count segments) max-segments)))
        (recur (subvec segments 1))
        segments))))

(defn lookup!
  "The complete page or the partial page plus continuation request for
  `window` from the walk's segments, or nil."
  [tier key window]
  (when (and tier key window)
    (let [hit (lru/lookup! (:store tier) key)
          answer (when (:found? hit)
                   (derive-window window (:segments (:value hit))))]
      (cond
        (:page answer) (do (meter! tier :hits) answer)
        (:partial answer) (do (meter! tier :partial-hits) answer)
        (:continuation answer) (do (meter! tier :resumes) answer)
        :else (do (meter! tier :misses) nil)))))

(defn publish!
  "Retains `page` (computed or composed for `window`) in the walk's
  segments, merging into an adjacent segment when it continues one."
  [tier key window page]
  (when (and tier key window (reusable-page? page) (seq (:data page)))
    (let [store (:store tier)
          candidate (segment-of window page)]
      (loop []
        (let [hit (lru/lookup! store key)
              resident (when (:found? hit) (:value hit))
              [segments outcome] (merge-segment (or (:segments resident) []) candidate)
              segments (bound-segments segments
                                       (:max-results-per-walk tier)
                                       (:max-segments-per-walk tier))]
          (when-not (= :covered outcome)
            (if resident
              (if (lru/replace-if! store key resident {:segments segments})
                (meter! tier (if (= :extension outcome) :extensions :supersessions))
                (recur))
              (if (lru/put-if-absent! store key {:segments segments})
                (meter! tier :deposits)
                (recur)))))))))
