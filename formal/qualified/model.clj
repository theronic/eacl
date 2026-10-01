(ns eacl.formal.qualified.model
  "Finite verification oracle only; never loaded by production. A qualified
   value maps each Boolean completion (world) of the residual atoms to one of
   Kleene's three truth values: true, false, or a fault's reason set
   (unknown), deliberately unlike the serving decision diagrams.

   A value is `{:worlds true-worlds}` plus optional exceptions: `:unknown`
   maps faulting worlds to reasons, and `:fault` is a reason set every
   unmentioned world takes (a total fault), with `:false` listing worlds that
   are nevertheless false. Composition is pointwise strong Kleene."
  (:require [clojure.set :as set]
            [eacl.formal.caveats.model :as lifecycle]))

(def maximum-fields 16)

(defn worlds [fields]
  ;; A JVM bit shift wraps its distance at the machine word width. Reject
  ;; unsupported finite domains instead of proving claims over an accidental
  ;; empty universe when the requested field count is too large.
  (when-not (and (integer? fields) (<= 0 fields maximum-fields))
    (throw (ex-info "Qualified model field count is outside its finite domain."
                    {:fields fields :maximum-fields maximum-fields})))
  (set (range (bit-shift-left 1 fields))))
(defn value [xs] {:worlds (set xs)})
(defn fault [reason] {:worlds #{} :fault #{reason}})

(defn- default-of [x] (or (:fault x) false))

(defn at
  "The truth value of `x` in world `w`: true, false, or a reason set."
  [x w]
  (cond (contains? (:worlds x) w) true
        (contains? (:unknown x) w) (get (:unknown x) w)
        (contains? (:false x) w) false
        :else (default-of x)))

(defn connective
  "Strong-Kleene connectives on one world; a fault is its reason set."
  [op a b]
  (let [unknown? set?
        unite (fn [] (cond (and (unknown? a) (unknown? b)) (set/union a b) (unknown? a) a :else b))]
    (case op
      :union (cond (or (true? a) (true? b)) true (or (unknown? a) (unknown? b)) (unite) :else false)
      (:intersection :arrow) (cond (or (false? a) (false? b)) false (or (unknown? a) (unknown? b)) (unite) :else true)
      :exclusion (cond (or (false? a) (true? b)) false (or (unknown? a) (unknown? b)) (unite) :else true))))

(defn- mentioned [x]
  (into (set (:worlds x)) (concat (keys (:unknown x)) (:false x))))

(defn outcome
  "The canonical value with `cells` (world -> truth value) over a default
   for every unmentioned world (false or a reason set)."
  [cells default]
  (when (true? default)
    (throw (ex-info "A true default has no finite representation." {})))
  (let [exceptions (into {} (remove (fn [[_ v]] (= v default))) cells)
        unknown (into {} (filter (fn [[_ v]] (set? v))) exceptions)
        falses (into #{} (keep (fn [[w v]] (when (false? v) w))) exceptions)]
    (cond-> {:worlds (into #{} (keep (fn [[w v]] (when (true? v) w))) exceptions)}
      (seq unknown) (assoc :unknown unknown)
      (seq falses) (assoc :false falses)
      (set? default) (assoc :fault default))))

(defn compose [op a b]
  (outcome (into {} (map (fn [w] [w (connective op (at a w) (at b w))]))
                 (set/union (mentioned a) (mentioned b)))
           (connective op (default-of a) (default-of b))))

(defn same?
  "Equal truth values in every world of `universe`."
  [universe a b]
  (every? #(= (at a %) (at b %)) universe))

(defn faulted?
  "Some world of `universe` faults."
  [universe x]
  (boolean (some #(set? (at x %)) universe)))

(defn errors [universe x]
  (into #{} (mapcat #(let [v (at x %)] (when (set? v) v))) universe))

(defn kind [universe x]
  (let [values (map #(at x %) universe)]
    (cond (some set? values) :failure
          (every? false? values) :no
          (every? true? values) :has
          :else :conditional)))

(defn atom-value [universe field]
  (value (set (filter #(bit-test % field) universe))))

(defn missing-fields [universe x fields]
  (if (= :conditional (kind universe x))
    (set (filter (fn [field]
                   (some (fn [w]
                           (not= (at x w) (at x (bit-flip w field))))
                         universe))
                 (range fields)))
    #{}))

(defn before? [time end] (or (nil? end) (< time end)))
(defn meet [a b] (cond (nil? a) b (nil? b) a :else (min a b)))
(defn later [a b] (when (and (some? a) (some? b)) (max a b)))
(defn evidence [v end] {:value v :end end :complete? true})
(defn no-new-faults?
  "In every world, a fault after is a fault before with at least its reasons."
  [before after]
  (let [kept? (fn [b a] (or (not (set? a)) (and (set? b) (set/subset? a b))))]
    (and (kept? (default-of before) (default-of after))
         (every? #(kept? (at before %) (at after %))
                 (set/union (mentioned before) (mentioned after))))))

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

(defn needed
  "Which certificate a composition keeps. A decisive complete witness decides
   whatever the other operand is, a fault included (strong Kleene); when both
   operands decide, either witness does, so the later deadline holds
   (QualifiedTemporal.Certificate)."
  [universe op a b]
  (let [ak (kind universe (:value a)) bk (kind universe (:value b))
        left? (and (:complete? a) (= ak (if (= :union op) :has :no)))
        right? (and (:complete? b) (= bk (if (#{:union :exclusion} op) :has :no)))]
    (cond
      (and left? right?) :later
      left? :left
      right? :right
      :else :both)))

(defn combine [universe op a b]
  (assoc (case (needed universe op a b)
           :later {:end (later (:end a) (:end b)) :complete? true}
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
  "Bounded positive SCC iteration: the least fixed point in the per-world
   order false < fault < true, accumulated by union. A stopped iteration is a
   typed failure, never a complete denial."
  [universe base rules budget]
  (loop [prior (zipmap (keys base) (repeat (evidence (value #{}) nil)))
         remaining budget]
    (if (zero? remaining)
      {:fault :work-limit :complete? false}
      (let [derived (recursive-step universe base rules prior)
            next (merge-with #(combine universe :union %1 %2) prior derived)]
        (if (= next prior)
          {:values next :complete? true}
          (recur next (dec remaining)))))))

(defn publishable? [e time]
  (and (:complete? e)
       (not (or (:fault (:value e)) (seq (:unknown (:value e)))))
       (before? time (:end e))))

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
