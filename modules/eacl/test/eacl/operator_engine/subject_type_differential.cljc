(ns eacl.operator-engine.subject-type-differential
  "Seeded differential over two subject types. Each seed is a random schema
  in which every user relation declares users, agents, both, or a wildcard
  (`user:*`, `agent:*`) of either, and a random store whose relationships
  expire at random times around the two evaluation times. Folder permissions
  are random unions, intersections and exclusions of those relations, of
  arrows to a subscription's relation and permission, of earlier permissions,
  and of recursion through `parent`.

  Operands therefore differ in the subject types they declare and in the
  subject types a wildcard reaches, so one sealed generator serves requests
  for a subject type that some operand, or the generator itself, does not
  declare. Every public read of every folder permission, for users and for
  agents, must equal an independent reference that evaluates the expressions
  directly over the relationships in force.

  Backend-agnostic: the caller supplies `new-store`, a function of client
  options returning a client over an empty store and an `add-objects!` that
  makes object ids resolvable."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as str]
            [eacl.core :as eacl]
            [eacl.engine.memoized-membership-refinement-test :as rng]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ^:private declarations
  "What a user relation may declare: `[subject-type form]` pairs."
  [#{[:user :concrete]}
   #{[:user :concrete]}
   #{[:agent :concrete]}
   #{[:user :concrete] [:agent :concrete]}
   #{[:user :wildcard]}
   #{[:agent :wildcard]}
   #{[:user :concrete] [:user :wildcard]}
   #{[:user :concrete] [:agent :wildcard]}
   #{[:user :wildcard] [:agent :wildcard]}
   #{[:user :concrete] [:agent :concrete] [:user :wildcard]}])

(def ^:private forms
  [[:user :concrete] [:user :wildcard] [:agent :concrete] [:agent :wildcard]])

(def ^:private subject-relations
  {:subscription [:everyone]
   :folder [:owner :viewer :editor :banned :public]})

(def ^:private object-relations
  "Relations whose subjects are objects, and their subject type."
  {:folder {:parent :folder :sub :subscription}})

(defn- canonical
  "An expression up to the order and repetition of union and intersection
  operands."
  [[kind & operands :as expression]]
  (case kind
    (:union :intersection)
    (let [flat (mapcat (fn [operand]
                         (let [operand (canonical operand)]
                           (if (= kind (first operand)) (rest operand) [operand])))
                       operands)
          flat (vec (sort-by pr-str (distinct flat)))]
      (if (= 1 (count flat)) (first flat) (into [kind] flat)))
    :exclusion [:exclusion (canonical (first operands)) (canonical (second operands))]
    expression))

(defn- random-leaf
  "A leaf of folder permission `self`: a relation, an arrow to the
  subscription, an earlier permission directly or through `parent`, or, where
  `recursive?`, `self` through `parent`."
  [state self earlier recursive?]
  (rng/pick state
            (cond-> [[:relation :owner] [:relation :viewer] [:relation :editor]
                     [:relation :banned] [:relation :public]
                     [:arrow :sub :everyone] [:arrow :sub :active]]
              (seq earlier) (into (map (fn [permission] [:permission permission])) earlier)
              (seq earlier) (into (map (fn [permission] [:arrow :parent permission])) earlier)
              recursive? (conj [:arrow :parent self]))))

(defn- random-expression
  "A random expression of at most `depth` operators. Recursion stays out of
  the subtracted operand of an exclusion, and an exclusion never subtracts
  an expression from itself."
  [state self earlier depth recursive?]
  (if (or (zero? depth) (rng/chance? state 25))
    (random-leaf state self earlier recursive?)
    (let [operand #(random-expression state self earlier (dec depth) %)]
      (case (rng/next-int! state 5)
        0 (into [:union] (repeatedly (+ 2 (rng/next-int! state 2)) #(operand recursive?)))
        (1 2) (into [:intersection] (repeatedly (+ 2 (rng/next-int! state 2)) #(operand recursive?)))
        3 (let [left (operand recursive?)
                right (first (remove #(= (canonical left) (canonical %))
                                     (repeatedly #(operand false))))]
            [:exclusion left right])
        4 (random-leaf state self earlier recursive?)))))

(defn random-schema
  "`{:relations {type {relation declaration}} :permissions {type [[name
  expression]]}}` for `seed`."
  [seed]
  (let [state (atom (+ 1000 seed))
        ;; Consecutive seeds start alike; a few draws separate them.
        _ (dotimes [_ 4] (rng/next-int! state 2))
        relations (into {}
                        (for [[type names] subject-relations]
                          [type (into {} (for [relation names]
                                           [relation (rng/pick state declarations)]))]))
        ;; A definition that no relation names is not stored, and a request
        ;; for it is refused as an unknown definition: both subject types
        ;; are declared somewhere.
        declared (into #{} (comp (mapcat vals) cat (map first)) (vals relations))
        relations (cond-> relations
                    (not= #{:user :agent} declared)
                    (assoc-in [:folder :owner] #{[:user :concrete] [:agent :concrete]}))
        total (+ 4 (rng/next-int! state 3))
        folder-permissions
        (loop [index 0 earlier [] permissions []]
          (if (= index total)
            permissions
            (let [self (keyword (str "pm" index))]
              (recur (inc index)
                     (conj earlier self)
                     (conj permissions
                           [self (random-expression state self earlier
                                                    (inc (rng/next-int! state 3)) true)])))))]
    {:relations relations
     :permissions {:subscription [[:active [:relation :everyone]]]
                   :folder folder-permissions}}))

(declare render-expression)

(defn- render-operand [expression]
  (if (contains? #{:union :intersection :exclusion} (first expression))
    (str "(" (render-expression expression) ")")
    (render-expression expression)))

(defn- render-expression [[kind a b :as expression]]
  (case kind
    (:relation :permission) (name a)
    :arrow (str (name a) "->" (name b))
    :union (str/join " + " (map render-operand (rest expression)))
    :intersection (str/join " & " (map render-operand (rest expression)))
    :exclusion (str (render-operand a) " - " (render-operand b))))

(defn- render-declaration [declaration]
  (str/join " | "
            (for [[subject-type form :as declared] forms
                  :when (contains? declaration declared)]
              (str (name subject-type) (when (= :wildcard form) ":*")))))

(defn render-schema [{:keys [relations permissions]}]
  (str "definition user {}\ndefinition agent {}\n"
       (apply str
              (for [type [:subscription :folder]]
                (str "definition " (name type) " {\n"
                     (apply str (for [[relation subject-type] (sort (get object-relations type))]
                                  (str "  relation " (name relation) ": " (name subject-type) "\n")))
                     (apply str (for [relation (get subject-relations type)]
                                  (str "  relation " (name relation) ": "
                                       (render-declaration (get-in relations [type relation]))
                                       "\n")))
                     (apply str (for [[permission expression] (get permissions type)]
                                  (str "  permission " (name permission) " = "
                                       (render-expression expression) "\n")))
                     "}\n")))))

;; ---------------------------------------------------------------------------
;; Stores
;; ---------------------------------------------------------------------------

(def ^:private wildcard "*")
(def ^:private subjects
  "Per subject type, the subjects that hold relationships and one that holds
  none."
  {:user ["u0" "u1" "u2" "uz"]
   :agent ["a0" "a1" "az"]})
(def ^:private subscriptions ["s0" "s1" "s2"])

(def ^:private t0 1790000000000)
(def evaluation-times
  "Before every random expiry in the store and between its two bands."
  [t0 (+ t0 10)])

(defn- random-expiry [state]
  (case (rng/next-int! state 10)
    0 (- t0 5)
    1 (+ t0 5)
    2 (+ t0 15)
    nil))

(defn random-store
  "`{:folders ids :relationships {[subject relation resource]
  valid-until-ms}}` for `seed` and its schema, objects as `[type id]`."
  [seed {:keys [relations]}]
  (let [state (atom (+ 500000 seed))
        _ (dotimes [_ 4] (rng/next-int! state 2))
        folders (mapv #(str "f" %) (range (+ 4 (rng/next-int! state 4))))
        objects {:subscription subscriptions :folder folders}
        candidates
        (concat
         (for [type [:subscription :folder]
               relation (get subject-relations type)
               :let [declaration (get-in relations [type relation])]
               object (get objects type)
               [subject-type form] forms
               :when (contains? declaration [subject-type form])
               subject (if (= :wildcard form)
                         [wildcard]
                         (butlast (get subjects subject-type)))]
           [[[subject-type subject] relation [type object]] (if (= :wildcard form) 35 28)])
         (for [folder folders subscription subscriptions]
           [[[:subscription subscription] :sub [:folder folder]] 30])
         (for [child folders parent folders :when (not= child parent)]
           [[[:folder parent] :parent [:folder child]] 16]))]
    {:folders folders
     :relationships
     (into {}
           (keep (fn [[key percent]]
                   (when (rng/chance? state percent)
                     [key (random-expiry state)])))
           candidates)}))

;; ---------------------------------------------------------------------------
;; The reference
;; ---------------------------------------------------------------------------

(defn- live
  [relationships time]
  (into #{}
        (keep (fn [[key valid-until-ms]]
                (when (or (nil? valid-until-ms) (< time valid-until-ms)) key)))
        relationships))

(defn- body [schema type permission]
  (some (fn [[name expression]] (when (= permission name) expression))
        (get-in schema [:permissions type])))

(defn- member?
  "A subject holds a relation through its own relationship or, when the
  relation declares the wildcard of its type, through the wildcard's. The
  wildcard's own decision (`*`) uses its relationships alone."
  [schema live [subject-type id :as subject] type relation resource]
  (or (contains? live [subject relation [type resource]])
      (and (not= wildcard id)
           (contains? (get-in schema [:relations type relation]) [subject-type :wildcard])
           (contains? live [[subject-type wildcard] relation [type resource]]))))

(defn- reference
  "`(holds? subject permission folder)` over `live`: each permission's least
  fixed point, earlier permissions first."
  [schema objects live]
  (let [memo (atom {})]
    (letfn [(evaluate [subject type expression resource pending]
              (let [[kind a b] expression]
                (case kind
                  :relation (boolean (member? schema live subject type a resource))
                  :permission (holds-on? subject type a resource pending)
                  :arrow (let [target (get-in object-relations [type a])]
                           (boolean
                            (some (fn [[[subject-type id] relation [resource-type resource-id]]]
                                    (and (= target subject-type) (= a relation)
                                         (= type resource-type) (= resource resource-id)
                                         (if (body schema target b)
                                           (holds-on? subject target b id pending)
                                           (member? schema live subject target b id))))
                                  live)))
                  :union (boolean (some #(evaluate subject type % resource pending)
                                        (rest expression)))
                  :intersection (every? #(evaluate subject type % resource pending)
                                        (rest expression))
                  :exclusion (and (evaluate subject type a resource pending)
                                  (not (evaluate subject type b resource pending))))))
            (holds-on? [subject type permission resource pending]
              (if (= [type permission] (:permission pending))
                (contains? (:holds pending) resource)
                (contains? (denotation subject type permission) resource)))
            (denotation [subject type permission]
              (let [key [subject type permission]]
                (or (get @memo key)
                    (let [expression (body schema type permission)
                          holds
                          (loop [holds #{}]
                            (let [grown (into #{}
                                              (filter #(evaluate subject type expression %
                                                                 {:permission [type permission]
                                                                  :holds holds}))
                                              (get objects type))]
                              (if (= grown holds) holds (recur grown))))]
                      (swap! memo assoc key holds)
                      holds))))]
      (fn [subject permission folder]
        (contains? (denotation subject :folder permission) folder)))))

;; ---------------------------------------------------------------------------
;; Public operations
;; ---------------------------------------------------------------------------

(defn- object [[type id]] (eacl/spice-object type id))

(defn- walk
  "Every item of a cursor walk in pages of `page-size`, forward or from the
  end, in the listing's order."
  [lookup query page-size direction]
  (loop [cursor nil items [] pages 0]
    (let [forward? (= :forward direction)
          page (lookup (cond-> (assoc query (if forward? :first :last) page-size)
                         cursor (assoc (if forward? :after :before) cursor)))
          ids (mapv :id (:data page))
          items (if forward? (into items ids) (into ids items))]
      (when (> pages 500)
        (throw (ex-info "Walk did not terminate." {:query query})))
      (if (get-in page [:page-info (if forward? :has-next-page? :has-previous-page?)])
        (recur (get-in page [:page-info (if forward? :end-cursor :start-cursor)])
               items (inc pages))
        items))))

(defn- subject-entries [client query]
  (loop [after nil entries [] pages 0]
    (let [page (eacl/lookup-subjects
                client (cond-> (assoc query :first 2) after (assoc :after after)))
          entries (into entries (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< pages 100))
        (recur (get-in page [:page-info :end-cursor]) entries (inc pages))
        entries))))

(defn- granted-by-listing
  "The subjects a listing grants: through their own entry or, unless
  excluded, through the `*` entry."
  [entries candidates]
  (let [by-id (into {} (map (juxt :id identity)) entries)
        excluded (set (map :id (:excluded-subjects (get by-id wildcard))))]
    (set (filter #(or (contains? by-id %)
                      (and (contains? by-id wildcard) (not (excluded %))))
                 candidates))))

(defn- check-resources
  [client holds? schema folders]
  (doseq [[permission _] (get-in schema [:permissions :folder])
          [subject-type ids] subjects
          id ids
          :let [subject [subject-type id]
                expected (set (filter #(holds? subject permission %) folders))
                query {:subject (object subject) :permission permission :resource/type :folder}
                lookup #(eacl/lookup-resources client %)
                whole (walk lookup query 100 :forward)]]
    (testing (str (name subject-type) " " id " " permission)
      (is (= expected (set whole)) "lookup-resources")
      (is (= (count expected) (count whole)) "no resource twice")
      (is (= whole (walk lookup query 1 :forward)) "pages of 1 follow the listing's order")
      (is (= whole (walk lookup (assoc query :evaluation :complete-denotation) 2 :backward))
          "pages from the end follow it too")
      (is (= (count expected) (:count (eacl/count-resources client query))) "count-resources")
      (is (= {:count (min 2 (count expected)) :truncated? (> (count expected) 2)}
             (select-keys (eacl/count-resources client (assoc query :count-limit 2))
                          [:count :truncated?]))
          "count-resources with a limit")
      (doseq [folder folders]
        (is (= (contains? expected folder)
               (eacl/can? client (object subject) permission (object [:folder folder])))
            (str "can? " folder))))))

(defn- check-subjects
  [client holds? schema folders]
  (doseq [[permission _] (get-in schema [:permissions :folder])
          folder folders
          [subject-type ids] subjects
          :let [query {:resource (object [:folder folder]) :permission permission
                       :subject/type subject-type}
                entries (subject-entries client query)
                listed (mapv :id entries)
                expected (set (filter #(holds? [subject-type %] permission folder) ids))]]
    (testing (str folder " " permission " " (name subject-type) " subjects")
      (is (= expected (granted-by-listing entries ids)) "lookup-subjects")
      (is (= (holds? [subject-type wildcard] permission folder)
             (contains? (set listed) wildcard))
          "the * entry")
      (is (every? #(or (= wildcard %) (contains? expected %)) listed)
          "only granted subjects are listed")
      (is (= (count listed) (count (distinct listed))) "no entry repeats")
      (is (= (count entries) (:count (eacl/count-subjects client query)))
          "count-subjects counts entries"))))

(defn run-seed!
  "Seeds one random schema and store and checks every read at both
  evaluation times."
  [{:keys [new-store]} seed]
  (let [now (atom t0)
        {:keys [client add-objects!]} (new-store {:clock #(deref now)})
        schema (random-schema seed)
        {:keys [folders relationships]} (random-store seed schema)
        objects {:subscription subscriptions :folder folders}]
    (add-objects! (concat (mapcat val subjects) subscriptions folders))
    (eacl/write-schema! client (render-schema schema))
    (eacl/create-relationships!
     client
     (mapv (fn [[[subject relation resource] valid-until-ms]]
             (cond-> (eacl/->Relationship (object subject) relation (object resource))
               valid-until-ms (assoc :valid-until-ms valid-until-ms)))
           relationships))
    (doseq [time evaluation-times
            :let [holds? (reference schema objects (live relationships time))]]
      (reset! now time)
      (testing (str "seed " seed ", time " time)
        (check-resources client holds? schema folders)
        (check-subjects client holds? schema folders)))))
