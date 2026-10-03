(ns eacl.operator-engine.wildcard-anchor-differential
  "Seeded differential for intersections whose operands differ in whether a
  wildcard can generate their candidates. A ledger is open to the users who
  may view it while a subscription, a plan of that subscription or the ledger
  itself grants every user (`user:*`); plans grant features the same way.

  Each seed is a random store over one schema. Relationships, wildcard ones
  included, expire at random times before, between and after the three
  evaluation times. Every public read of every ledger permission must equal
  an independent reference: it evaluates the schema's expressions directly
  over the relationships in force, a concrete subject holding a relation
  through its own relationship or, where the relation declares `user:*`,
  through the wildcard's. The schema text is rendered from the expressions
  the reference evaluates.

  Backend-agnostic: the caller supplies `new-store`, a function of client
  options returning a client over an empty store and an `add-objects!` that
  makes object ids resolvable."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as str]
            [eacl.core :as eacl]
            [eacl.engine.memoized-membership-refinement-test :as rng]))

;; ---------------------------------------------------------------------------
;; The schema, as data
;; ---------------------------------------------------------------------------

(def ^:private relations
  "Per type, each relation and its subjects: an object type, or how it admits
  users (`:concrete`, `:wildcard` for `user:*` alone, `:mixed` for both)."
  {:plan [[:subscriber :wildcard] [:history :wildcard] [:sharing :wildcard]]
   :subscription [[:plan :plan] [:everyone :wildcard]]
   :ledger [[:owner :concrete] [:viewer :concrete] [:shared :mixed] [:banned :concrete]
            [:subscription :subscription] [:subscriber :wildcard]]})

(def ^:private permissions
  "Per type, each permission and its expression. In the intersections whose
  comment says so, the operand a wildcard reaches was the generator before
  generators avoided wildcards."
  {:subscription
   [[:subscriber [:arrow :plan :subscriber]]
    [:history [:arrow :plan :history]]
    [:sharing [:arrow :plan :sharing]]]
   :ledger
   [[:view [:union [:relation :owner] [:relation :viewer]]]
    [:subscribed [:arrow :subscription :everyone]]
    [:planned [:arrow :subscription :subscriber]]
    ;; A named operand that sorts before `view`: moved.
    [:open [:intersection [:permission :view] [:permission :subscribed]]]
    [:open_swapped [:intersection [:permission :subscribed] [:permission :view]]]
    [:open_inline [:intersection [:permission :view] [:arrow :subscription :everyone]]]
    ;; A wildcard relation on the ledger: moved.
    [:open_direct [:intersection [:permission :view] [:relation :subscriber]]]
    [:open_shared [:intersection [:permission :view] [:relation :shared]]]
    ;; Two arrows to the wildcard: moved.
    [:open_planned [:intersection [:permission :view] [:permission :planned]]]
    [:keep_history [:intersection [:permission :view] [:arrow :subscription :history]]]
    [:share [:intersection [:relation :owner] [:arrow :subscription :sharing]]]
    ;; A wildcard reaches both operands.
    [:public_open [:intersection [:relation :shared] [:permission :subscribed]]]
    ;; Under an exclusion and a union.
    [:open_unbanned [:exclusion [:intersection [:permission :view] [:permission :subscribed]]
                     [:relation :banned]]]
    [:open_or_shared [:union [:intersection [:permission :view] [:permission :subscribed]]
                      [:relation :shared]]]
    ;; A wildcard generates an exclusion from its left operand.
    [:trial [:exclusion [:relation :subscriber] [:relation :owner]]]]})

(defn- relation-subjects [type relation]
  (some (fn [[name subjects]] (when (= relation name) subjects)) (get relations type)))

(defn- permission-body [type permission]
  (some (fn [[name body]] (when (= permission name) body)) (get permissions type)))

(defn- declares-wildcard? [type relation]
  (contains? #{:wildcard :mixed} (relation-subjects type relation)))

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
    :intersection (str (render-operand a) " & " (render-operand b))
    :exclusion (str (render-operand a) " - " (render-operand b))))

(def schema
  (str "definition user {}\n"
       (apply str
              (for [type [:plan :subscription :ledger]]
                (str "definition " (name type) " {\n"
                     (apply str
                            (for [[relation subjects] (get relations type)]
                              (str "  relation " (name relation) ": "
                                   (case subjects
                                     :concrete "user"
                                     :wildcard "user:*"
                                     :mixed "user | user:*"
                                     (name subjects))
                                   "\n")))
                     (apply str
                            (for [[permission body] (get permissions type)]
                              (str "  permission " (name permission) " = "
                                   (render-expression body) "\n")))
                     "}\n")))))

;; ---------------------------------------------------------------------------
;; Stores
;; ---------------------------------------------------------------------------

(def ^:private users ["u0" "u1" "u2"])
(def ^:private unmentioned "zz")
(def ^:private wildcard "*")
(def ^:private listed-users (conj users unmentioned))
(def ^:private plans ["basic" "pro" "business"])

(def ^:private t0 1790000000000)
(def evaluation-times
  "Before any random expiry, between the two expiry bands, and after both."
  [t0 (+ t0 10) (+ t0 40)])

(defn- random-expiry
  "No expiry, an already expired one, or one inside either later band."
  [state]
  (case (rng/next-int! state 8)
    0 (- t0 1 (rng/next-int! state 5))
    1 (+ t0 1 (rng/next-int! state 9))
    2 (+ t0 11 (rng/next-int! state 29))
    nil))

(defn store
  "A seeded store: `{:ledgers ids :subscriptions ids :relationships
  {[subject relation resource] valid-until-ms}}`, objects as `[type id]`."
  [seed]
  (let [state (atom (inc seed))
        ;; Consecutive seeds start alike; a few draws separate them.
        _ (dotimes [_ 4] (rng/next-int! state 2))
        ledgers (mapv #(str "l" %) (range (+ 5 (rng/next-int! state 6))))
        subscriptions (mapv #(str "s" %) (range (+ 3 (rng/next-int! state 4))))
        candidates
        (concat
         ;; Plans grant their features to every user: basic the least.
         (for [[plan features] [["basic" [:subscriber]]
                                ["pro" [:subscriber :history]]
                                ["business" [:subscriber :history :sharing]]]
               feature features]
           [[[:user wildcard] feature [:plan plan]] 90])
         (for [subscription subscriptions plan plans]
           [[[:plan plan] :plan [:subscription subscription]] 30])
         (for [subscription subscriptions]
           [[[:user wildcard] :everyone [:subscription subscription]] 60])
         (for [ledger ledgers subscription subscriptions]
           [[[:subscription subscription] :subscription [:ledger ledger]] 25])
         (for [ledger ledgers]
           [[[:user wildcard] :subscriber [:ledger ledger]] 45])
         (for [ledger ledgers]
           [[[:user wildcard] :shared [:ledger ledger]] 25])
         (for [ledger ledgers user users
               [relation percent] [[:owner 30] [:viewer 25] [:shared 20] [:banned 15]]]
           [[[:user user] relation [:ledger ledger]] percent]))]
    {:ledgers ledgers
     :subscriptions subscriptions
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
  "The relationship keys in force at `time`."
  [relationships time]
  (into #{}
        (keep (fn [[key valid-until-ms]]
                (when (or (nil? valid-until-ms) (< time valid-until-ms)) key)))
        relationships))

(defn- member?
  [live subject type relation resource]
  (or (contains? live [[:user subject] relation [type resource]])
      (and (not= wildcard subject)
           (declares-wildcard? type relation)
           (contains? live [[:user wildcard] relation [type resource]]))))

(defn- intermediates
  "The objects of `target-type` that hold `via` on `resource`."
  [live type via resource target-type]
  (keep (fn [[[subject-type subject] relation [resource-type resource-id]]]
          (when (and (= target-type subject-type) (= via relation)
                     (= type resource-type) (= resource resource-id))
            subject))
        live))

(defn- holds?
  [live subject type expression resource]
  (let [[kind a b] expression]
    (case kind
      :relation (boolean (member? live subject type a resource))
      :permission (holds? live subject type (permission-body type a) resource)
      :arrow (let [target-type (relation-subjects type a)]
               (boolean
                (some (fn [intermediate]
                        (if-let [body (permission-body target-type b)]
                          (holds? live subject target-type body intermediate)
                          (member? live subject target-type b intermediate)))
                      (intermediates live type a resource target-type))))
      :union (boolean (some #(holds? live subject type % resource) (rest expression)))
      :intersection (every? #(holds? live subject type % resource) (rest expression))
      :exclusion (and (holds? live subject type a resource)
                      (not (holds? live subject type b resource))))))

(defn permitted?
  "Whether `subject` (a user id, or `*` for the wildcard's own decision)
  holds `permission` on ledger `ledger` over the `live` relationships."
  [live subject permission ledger]
  (holds? live subject :ledger (permission-body :ledger permission) ledger))

;; ---------------------------------------------------------------------------
;; Public operations
;; ---------------------------------------------------------------------------

(defn- object [[type id]] (eacl/spice-object type id))

(defn- walk
  [lookup query page-size]
  (loop [after nil items [] pages 0]
    (let [page (lookup (cond-> (assoc query :first page-size) after (assoc :after after)))
          items (into items (map :id) (:data page))]
      (when (> pages 500)
        (throw (ex-info "Walk did not terminate." {:query query})))
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) items (inc pages))
        items))))

(defn- granted-by-listing
  "The listed users a subject listing grants: its own entry or, unless
  excluded, the `*` entry."
  [entries]
  (let [by-id (into {} (map (juxt :id identity)) entries)
        excluded (set (map :id (:excluded-subjects (get by-id wildcard))))]
    (set (filter #(or (contains? by-id %)
                      (and (contains? by-id wildcard) (not (excluded %))))
                 listed-users))))

(defn- check-resources
  [client live ledgers cache?]
  (doseq [[permission _] (get permissions :ledger)
          user listed-users
          :let [expected (set (filter #(permitted? live user permission %) ledgers))
                query {:subject (object [:user user]) :permission permission
                       :resource/type :ledger :cache? cache?}
                walks (mapv #(walk (fn [request] (eacl/lookup-resources client request)) query %)
                            [1 3 100])]]
    (testing (str user " " permission)
      (is (= expected (set (first walks))) "lookup-resources")
      (is (apply = walks) "every page size walks the same sequence")
      (is (= (count expected) (count (first walks))) "no resource twice")
      (is (= (count expected)
             (:count (eacl/count-resources client (dissoc query :cache?))))
          "count-resources")
      (doseq [ledger ledgers
              :let [allowed? (contains? expected ledger)]]
        (is (= allowed? (eacl/can? client (object [:user user]) permission
                                   (object [:ledger ledger])))
            (str "can? " ledger))))))

(defn- check-subjects
  [client live ledgers cache?]
  (doseq [[permission _] (get permissions :ledger)
          ledger ledgers
          :let [query {:resource (object [:ledger ledger]) :permission permission
                       :subject/type :user}
                entries (loop [after nil entries [] pages 0]
                          (let [page (eacl/lookup-subjects
                                      client (cond-> (assoc query :first 2 :cache? cache?)
                                               after (assoc :after after)))
                                entries (into entries (:data page))]
                            (if (and (get-in page [:page-info :has-next-page?]) (< pages 100))
                              (recur (get-in page [:page-info :end-cursor]) entries (inc pages))
                              entries)))
                ids (mapv :id entries)
                expected (set (filter #(permitted? live % permission ledger) listed-users))]]
    (testing (str ledger " " permission " subjects")
      (is (= expected (granted-by-listing entries)) "lookup-subjects")
      (is (= (permitted? live wildcard permission ledger) (contains? (set ids) wildcard))
          "the * entry")
      (is (every? #(or (= wildcard %) (contains? expected %)) ids)
          "only granted subjects are listed")
      (is (= (count ids) (count (distinct ids))) "no entry repeats")
      (is (= (count entries) (:count (eacl/count-subjects client query)))
          "count-subjects counts entries"))))

(defn run-seed!
  "Seeds one random store and checks every read at every evaluation time,
  with and without the client's cache."
  [{:keys [new-store]} seed]
  (let [now (atom t0)
        {:keys [client add-objects!]} (new-store {:clock #(deref now)})
        {:keys [ledgers subscriptions relationships]} (store seed)]
    (add-objects! (concat users [unmentioned] plans subscriptions ledgers))
    (eacl/write-schema! client schema)
    (eacl/create-relationships!
     client
     (mapv (fn [[[subject relation resource] valid-until-ms]]
             (cond-> (eacl/->Relationship (object subject) relation (object resource))
               valid-until-ms (assoc :valid-until-ms valid-until-ms)))
           relationships))
    (doseq [time evaluation-times
            cache? [false true]
            :let [live (live relationships time)]]
      (reset! now time)
      (testing (str "seed " seed ", time " time ", cache? " cache?)
        (check-resources client live ledgers cache?)
        (check-subjects client live ledgers cache?)))))
