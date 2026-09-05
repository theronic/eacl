(ns eacl.formal.qualified.model
  "Finite verification oracle only; never loaded by production. Residuals are
   sets of Boolean completions, deliberately unlike the serving encoding."
  (:require [clojure.set :as set]
            [eacl.formal.caveats.model :as lifecycle]))

(defn worlds [fields] (set (range (bit-shift-left 1 fields))))
(defn value [xs] {:worlds xs})
(defn fault [reason] {:fault #{reason}})
(defn kind [universe x]
  (cond (:fault x) :failure
        (empty? (:worlds x)) :no
        (= universe (:worlds x)) :has
        :else :conditional))

(defn atom-value [universe field]
  (value (set (filter #(bit-test % field) universe))))

(defn compose [op a b]
  (if (or (:fault a) (:fault b))
    {:fault (set/union (:fault a #{}) (:fault b #{}))}
    (value ((case op :union set/union :exclusion set/difference
                  (:intersection :arrow) set/intersection)
            (:worlds a) (:worlds b)))))

(defn missing-fields [universe x fields]
  (if (= :conditional (kind universe x))
    (set (filter (fn [field]
                   (some (fn [w]
                           (not= (contains? (:worlds x) w)
                                 (contains? (:worlds x) (bit-flip w field))))
                         universe))
                 (range fields)))
    #{}))

(defn before? [time end] (or (nil? end) (< time end)))
(defn meet [a b] (cond (nil? a) b (nil? b) a :else (min a b)))
(defn evidence [v end] {:value v :end end :complete? true})
(defn no-new-faults? [before after]
  (or (not (:fault after))
      (and (:fault before) (set/subset? (:fault after) (:fault before)))))

(defn qualify [universe qualifiers qid time]
  (if (nil? qid)
    (evidence (value universe) nil)
    (let [q (get qualifiers qid)]
      (cond
        (not (:valid? q)) (evidence (fault :invalid-qualifier) nil)
        (not (before? time (:expiry q))) (evidence (value #{}) nil)
        :else (evidence (:caveat q) (:expiry q))))))

(defn edge [universe state identity time]
  (let [f (:forward state) r (:reverse state)]
    (cond
      (and (not (contains? f identity)) (not (contains? r identity)))
      (evidence (value #{}) nil)
      (or (not= (contains? f identity) (contains? r identity))
          (not= (get f identity) (get r identity)))
      (evidence (fault :asymmetric-pair) nil)
      :else (qualify universe (:qualifiers state) (get f identity) time))))

(defn needed [universe op a b]
  (let [ak (kind universe (:value a)) bk (kind universe (:value b))]
    (cond
      (or (= :failure ak) (= :failure bk)) :both
      (and (:complete? a) (= ak (if (= :union op) :has :no))) :left
      (and (:complete? b) (= bk (if (#{:union :exclusion} op) :has :no))) :right
      :else :both)))

(defn combine [universe op a b]
  (assoc (case (needed universe op a b)
           :left (select-keys a [:end :complete?])
           :right (select-keys b [:end :complete?])
           :both {:end (meet (:end a) (:end b))
                  :complete? (and (:complete? a) (:complete? b))})
         :value (compose op (:value a) (:value b))))

(defn evaluate [universe state tree time budget]
  (letfn [(walk [[op a b] remaining]
            (if (zero? remaining)
              [(assoc (evidence (fault :work-limit) nil) :complete? false) 0]
              (case op
                :edge [(edge universe state a time) (dec remaining)]
                :constant [(evidence a nil) (dec remaining)]
                (let [[left n] (walk a (dec remaining))
                      [right m] (walk b n)]
                  [(combine universe op left right) m]))))]
    (first (walk tree budget))))

(defn lifecycle-state
  "Connect the already certified storage transitions to the temporal oracle.
   The extra semantic table stands for decoding valid native qualifier facts."
  [state semantics]
  (assoc state :qualifiers
         (into {} (map (fn [[qid _]] [qid (get semantics qid)]) (:qualifiers state)))))

(defn stored-transition [state action] (lifecycle/transition state action))

(defn recursive-step [universe base rules prior]
  (reduce-kv
    (fn [out node initial]
      (assoc out node
             (reduce (fn [acc [edge-evidence target]]
                       (combine universe :union acc
                                (combine universe :arrow edge-evidence (get prior target))))
                     initial (get rules node))))
    {} base))

(defn fixed-point
  "Bounded positive SCC iteration. Fault propagation is an additional finite
   component; a stopped iteration is a typed failure, never a complete denial."
  [universe base rules budget]
  (loop [prior (zipmap (keys base) (repeat (evidence (value #{}) nil)))
         remaining budget]
    (if (zero? remaining)
      {:fault :work-limit :complete? false}
      (let [next (recursive-step universe base rules prior)]
        (if (= next prior)
          {:values next :complete? true}
          (recur next (dec remaining)))))))

(defn publishable? [e time]
  (and (:complete? e) (not (:fault (:value e))) (before? time (:end e))))

(def scope-fields
  [:source :schema :relations :qualifiers :context :evaluator :policy :abi :query])

(defn scope-valid? [scope] (every? #(contains? scope %) scope-fields))

(defn accept-cache? [universe entry selected]
  (let [{:keys [scope basis ancestors time]} selected
        {:keys [start evidence]} entry]
    (boolean
      (and (:authenticated? entry) (scope-valid? scope) (= scope (:scope entry))
           (before? start (:end evidence))
           (or (= basis (:basis entry)) (contains? ancestors (:basis entry)))
           (= (:kind entry) (kind universe (:value evidence)))
           (not= :failure (:kind entry))
           (or (and (= basis (:basis entry)) (= time start))
               (and (:complete? evidence) (<= start time) (before? time (:end evidence))))))))

(defn cursor-decision [universe cursor selected]
  (let [{:keys [entry mode token-expiry retained-complete?]} cursor
        {:keys [time wall-time key-available? basis]} selected]
    (cond
      (not (and key-available? (< wall-time token-expiry))) :invalid-token
      (not= (:scope entry) (:scope selected)) :scope-mismatch
      (not (accept-cache? universe entry selected)) :restart-required
      (= :pinned mode) (if (and (= basis (:basis entry)) (= time (:start entry)))
                        :continue :restart-required)
      (= :live mode) (if (and (<= (:start entry) time)
                             (or (= (:start entry) time)
                                 (and retained-complete? (get-in entry [:evidence :complete?])
                                      (before? time (get-in entry [:evidence :end])))))
                      :continue :restart-required)
      :else :invalid-token)))

(defn cursor-certificate
  "All retained roles, including skipped bans, participate. No unseen suffix
   is fetched to invent completeness. Callers explicitly report missing roles."
  [examined retained complete?]
  {:end (reduce meet nil (map :end (concat examined retained)))
   :complete? (and complete? (every? :complete? (concat examined retained)))})

(defn accept-decode? [old selected]
  (and (= (select-keys old [:source :qid :format])
          (select-keys selected [:source :qid :format]))
       (or (= (:basis old) (:basis selected))
           (and (:writer-certified? old) (:writer-certified? selected)
                (integer? (:version old)) (pos? (:version old)) (= (:version old) (:version selected))
                (= (:relation old) (:relation selected)))
           (and (seq (:content old)) (= (:content old) (:content selected))))))

(defn capture-time [high-water raw-sample] (max high-water raw-sample))
