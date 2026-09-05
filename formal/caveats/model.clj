(ns eacl.formal.caveats.model
  "Proof-only finite oracle. Never required by a production namespace."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]))

(def profile (edn/read-string (slurp "formal/caveats/profile.edn")))
(def limits (:bounds profile))
(def scalar-types (set (:scalar-types profile)))

(defn utf8-size [s] (alength (.getBytes ^String s "UTF-8")))

(defn valid-text? [s]
  (and (string? s)
       (<= (utf8-size s) (:string-utf8-bytes limits))
       ;; Java's codePoints retains an unpaired surrogate as a code point.
       (not-any? #(<= 55296 % 57343) (.toArray (.codePoints ^String s)))))

(defn exact-integer? [x lo hi]
  (and (integer? x) (<= lo x hi)))

(defn parameter-type? [t]
  (or (contains? scalar-types t)
      (and (vector? t) (= 2 (count t)) (= :list (first t))
           (contains? scalar-types (second t)))
      (and (vector? t) (= 3 (count t)) (= [:map :string] (subvec t 0 2))
           (contains? scalar-types (nth t 2)))))

(defn value-valid? [t v]
  (case t
    :bool (boolean? v)
    :int (exact-integer? v (:integer-min limits) (:integer-max limits))
    :string (valid-text? v)
    :timestamp (and (vector? v) (= 2 (count v)) (= :timestamp (first v))
                    (exact-integer? (second v) (:timestamp-min-ms limits) (:timestamp-max-ms limits)))
    (and (parameter-type? t)
         (if (= :list (first t))
           (and (vector? v) (<= (count v) (:container-entries limits))
                (every? #(value-valid? (second t) %) v))
           (and (map? v) (<= (count v) (:container-entries limits))
                (every? (fn [[k x]] (and (valid-text? k) (value-valid? (nth t 2) x))) v))))))

(defn value-size [t v]
  (case t
    :string (utf8-size v)
    (:bool :int :timestamp) 1
    (if (= :list (first t))
      (reduce + 0 (map #(value-size (second t) %) v))
      (reduce + 0 (map (fn [[k x]] (+ (utf8-size k) (value-size (nth t 2) x))) v)))))

(defn max-size [t]
  (case t
    :string (:string-utf8-bytes limits)
    (:bool :int :timestamp) 1
    (if (= :list (first t))
      (* (:container-entries limits) (max-size (second t)))
      (* (:container-entries limits) (+ (:string-utf8-bytes limits) (max-size (nth t 2)))))))

(defn plan-type [parameters [op & args :as plan]]
  (let [ts (mapv #(plan-type parameters %) (if (#{:literal :param} op) [] args))
        [a b] ts]
    (cond
      ;; Source literals are scalar; partial residuals may substitute a typed
      ;; list/map parameter. These remain values, never source-level literals.
      (and (= :literal op) (= 3 (count plan)) (parameter-type? (first args))
           (value-valid? (first args) (second args))) (first args)
      (and (= :param op) (= 2 (count plan)) (contains? parameters (first args)))
      (get parameters (first args))
      (and (= :not op) (= [:bool] ts)) :bool
      (and (#{:and :or} op) (= [:bool :bool] ts)) :bool
      (and (#{:eq :ne} op) (= 2 (count args)) (= a b) (contains? scalar-types a)) :bool
      (and (#{:lt :le :gt :ge} op) (= 2 (count args)) (= a b) (#{:int :timestamp} a)) :bool
      (and (#{:contains :starts-with :ends-with} op) (= [:string :string] ts)) :bool
      (and (= :index op) (= 2 (count args)) (vector? a) (= [:map :string] (subvec a 0 2))
           (= :string b)) (nth a 2)
      (and (= :in op) (= 2 (count args))
           (or (= b [:list a]) (and (= a :string) (vector? b) (= :map (first b))))) :bool
      :else :invalid)))

(defn shape [[op & args]]
  (if (#{:literal :param} op)
    {:nodes 1 :depth 1}
    (let [children (map shape args)]
      {:nodes (inc (reduce + 0 (map :nodes children)))
       :depth (inc (reduce max 0 (map :depth children)))})))

(defn selected-value [plan context]
  (case (first plan)
    :literal {:present true :value (nth plan 2)}
    :param (if (contains? context (second plan)) {:present true :value (get context (second plan))} {})
    {}))

(defn estimate-work [parameters plan context]
  (let [[op & args] plan
        [a b] args
        size (fn [p]
               (let [t (plan-type parameters p) selected (selected-value p context)]
                 (if (:present selected) (value-size t (:value selected)) (max-size t))))]
    (if (#{:literal :param} op)
      1
      (let [child-cost (reduce + (map #(estimate-work parameters % context) args))
            cost (cond
                   (= op :contains) (* (size a) (size b))
                   (= op :in) (let [t (plan-type parameters b)]
                                (+ (size b) (* (if (= :list (first t)) (:container-entries limits) 1) (size a))))
                   (#{:eq :ne :lt :le :gt :ge :index :starts-with :ends-with} op) (+ (size a) (size b))
                   :else 0)]
        (min (inc (:work-units limits)) (+ 1 child-cost cost))))))

(defn fault [reason] {:fault reason})
(defn known [type value] {:type type :value value :residual [:literal type value]})
(defn missing [fields residual] {:missing fields :residual residual})
(defn has-value? [r] (contains? r :value))

(defn logical [op a b]
  (let [absorber (= :or op)
        absorbed? #(and (has-value? %) (= absorber (:value %)))
        identity? #(and (has-value? %) (= (not absorber) (:value %)))]
    (cond
      (or (absorbed? a) (absorbed? b)) (known :bool absorber)
      (or (:fault a) (:fault b)) (fault (first (sort (keep :fault [a b]))))
      (identity? a) b
      (identity? b) a
      :else (missing (set/union (:missing a) (:missing b)) [op (:residual a) (:residual b)]))))

(defn concrete [op a b]
  (let [x (:value a) y (:value b)
        scalar #(if (= :timestamp (:type %)) (second (:value %)) (:value %))]
    (case op
      :eq (known :bool (= x y))
      :ne (known :bool (not= x y))
      :lt (known :bool (< (scalar a) (scalar b)))
      :le (known :bool (<= (scalar a) (scalar b)))
      :gt (known :bool (> (scalar a) (scalar b)))
      :ge (known :bool (>= (scalar a) (scalar b)))
      :in (known :bool (if (map? y) (contains? y x) (boolean (some #(= x %) y))))
      :index (if (contains? x y) (known (nth (:type a) 2) (get x y)) (fault :missing-map-key))
      :contains (known :bool (str/includes? x y))
      :starts-with (known :bool (str/starts-with? x y))
      :ends-with (known :bool (str/ends-with? x y))
      (fault :unsupported-operation))))

(defn partial-value [parameters [op & args :as plan] context]
  (case op
    :literal (known (first args) (second args))
    :param (if (contains? context (first args))
             (known (get parameters (first args)) (get context (first args)))
             (missing #{(first args)} plan))
    (let [children (mapv #(partial-value parameters % context) args)
          [a b] children]
      (cond
        (#{:and :or} op) (logical op a b)
        (some :fault children) (fault (first (sort (keep :fault children))))
        (some :missing children) (missing (apply set/union #{} (keep :missing children))
                                         (into [op] (map :residual children)))
        (= :not op) (known :bool (not (:value a)))
        :else (concrete op a b)))))

(defn evaluate [parameters plan request bound]
  (let [context (merge request bound)
        supplied (concat request bound)
        {:keys [nodes depth]} (shape plan)
        invalid-context (or (not (map? request)) (not (map? bound))
                            (some (fn [[k v]] (or (not (contains? parameters k))
                                                 (not (value-valid? (get parameters k) v)))) supplied))]
    (cond
      invalid-context {:outcome :error :reason :context-type}
      (not= :bool (plan-type parameters plan)) {:outcome :error :reason :unsupported-overload}
      (or (> nodes (:plan-nodes limits)) (> depth (:plan-depth limits))
          (> (+ (estimate-work parameters plan context)
                (reduce + 0 (map (fn [[k v]] (+ 1 (value-size (get parameters k) v))) context)))
             (:work-units limits))) {:outcome :error :reason :resource-limit}
      :else (let [r (partial-value parameters plan context)]
              (cond (:fault r) {:outcome :error :reason (:fault r)}
                    (:missing r) {:outcome :conditional :missing-fields (:missing r) :residual (:residual r)}
                    :else {:outcome (if (:value r) :true :false)})))))

(def empty-state {:forward {} :reverse {} :qualifiers {} :allocated #{} :generation 0 :facts #{}})

(defn normalized-qualifier [q]
  (let [allowed #{:caveat :caveat-context :valid-until-ms}
        caveat (:caveat q) context (:caveat-context q) until (:valid-until-ms q)]
    (cond
      (not (map? q)) (fault :qualifier-shape)
      (seq (set/difference (set (keys q)) allowed)) (fault :qualifier-unknown-field)
      (and (contains? q :caveat-context) (nil? caveat)) (fault :context-without-caveat)
      (and (some? caveat) (not (and (integer? caveat) (pos? caveat)))) (fault :qualifier-ref)
      (and (some? context) (not (map? context))) (fault :qualifier-context)
      (and (some? until) (not (exact-integer? until (:timestamp-min-ms limits) (:timestamp-max-ms limits))))
      (fault :qualifier-time)
      :else (let [out (cond-> {}
                        (some? caveat) (assoc :caveat caveat)
                        (seq context) (assoc :caveat-context context)
                        (some? until) (assoc :valid-until-ms until))]
              (when (seq out) out)))))

(defn healthy? [{:keys [forward reverse qualifiers allocated generation]}]
  (let [owners (remove nil? (vals forward))]
    (and (= forward reverse)
         (set/subset? (set (keys qualifiers)) allocated)
         (= (count owners) (count (set owners)))
         (every? #(contains? qualifiers %) owners)
         (every? (fn [[qid {:keys [value created]}]]
                   (and (integer? qid) (pos? qid) (<= created generation)
                        (seq value) (= value (normalized-qualifier value)))) qualifiers))))

(defn transition [s [op identity qid value facts]]
  (let [owned (set (remove nil? (vals (:forward s))))
        attachable (or (nil? qid) (and (contains? (:qualifiers s) qid) (not (contains? owned qid))))
        prior (get-in s [:forward identity])
        admitted
        (case op
          :prepare (and (pos-int? qid) (not (contains? (:allocated s) qid))
                        (seq value) (= value (normalized-qualifier value)))
          :publish (and (not (contains? (:forward s) identity)) attachable)
          :replace (and (contains? (:forward s) identity) attachable)
          :delete (contains? (:forward s) identity)
          :cleanup (and (contains? (:qualifiers s) qid) (not (contains? owned qid)))
          false)]
    (if-not (and (healthy? s) admitted)
      {:accepted false :state s}
      (let [generation (inc (:generation s))
            changed
            (case op
              :prepare (-> s (assoc-in [:qualifiers qid] {:value value :created generation})
                           (update :allocated conj qid))
              (:publish :replace) (-> s
                                      (assoc-in [:forward identity] qid)
                                      (assoc-in [:reverse identity] qid)
                                      (update :facts into facts)
                                      (cond-> (= :replace op) (update :qualifiers dissoc prior)))
              :delete (-> s (update :forward dissoc identity) (update :reverse dissoc identity)
                          (update :qualifiers dissoc prior))
              :cleanup (update s :qualifiers dissoc qid))]
        {:accepted true :state (assoc changed :generation generation)}))))

(defn repair-peer [state identity]
  (let [forward (get-in state [:forward identity]) reverse (get-in state [:reverse identity])
        qid (or forward reverse)
        exactly-one? (not= (contains? (:forward state) identity) (contains? (:reverse state) identity))
        complete (-> state (assoc-in [:forward identity] qid) (assoc-in [:reverse identity] qid))]
    (if (and exactly-one? qid (healthy? complete))
      {:accepted true :state (update complete :generation inc)}
      {:accepted false :state state})))

(defn valid-definitions? [definitions]
  (and (= (count definitions) (count (set (map :name definitions))))
       (every? (fn [{:keys [name parameters plan profile-version]}]
                 (and (string? name) (= "eacl-cel/1" profile-version)
                      (<= (count parameters) (:parameters limits))
                      (= (count parameters) (count (set (map first parameters))))
                      (every? parameter-type? (map second parameters))
                      (= :bool (plan-type (into {} parameters) plan)))) definitions)))

(defn schema-result [selected expected-generation definitions allowances retained]
  (let [names (set (map :name definitions))]
    (if (and (= expected-generation (:generation selected))
             (valid-definitions? definitions)
             (every? #(set/subset? (disj % nil) names) (vals allowances))
             (set/subset? retained names))
      {:accepted true
       :selected {:generation (inc expected-generation)
                  :definitions (into {} (map (juxt :name identity)) definitions)
                  :allowances allowances}}
      {:accepted false :selected selected})))

(defn allowed? [schema relation caveat]
  (contains? (get-in schema [:allowances relation] #{}) caveat))
