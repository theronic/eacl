(ns eacl.datascript.wildcard-operator-routes-test
  "Seeded differential of wildcard subjects through the operator engine's
  recursive routes against an independent stratified least fixed point.

  Each case is a random schema over one folder hierarchy (with cycles):
  operators over the recursive union-only `readable` and `granted` (delegated
  union operands, union roots and flattened generators), one member that
  recurses through a linear guard, members that consult another permission,
  and a member that recurses through two operands (the tabled evaluator).
  Most user relations declare `user:*`; some cases also declare a Caveated
  wildcard branch and a Caveated concrete branch. Relationships, including
  wildcard ones, expire at random times between the evaluation times.

  One caching client answers checks, batched checks, resource walks and
  counts, and subject walks and counts at each evaluation time, with a
  Caveat context when the schema declares the Caveat. Midway its cache is
  exported and restored into a new client. Random writes and deletes of
  wildcard and concrete relationships then follow, and the same client
  answers again. Every answer must equal the reference: a concrete subject
  holds a relation through its own relationship or, when the relation
  declares `user:*`, through the wildcard's; the wildcard's own decision uses
  its relationships only. A subject listing grants a user through its own
  entry or, unless excluded, the `*` entry."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.memoized-membership-refinement-test :as rng]
            [eacl.engine.stable-route :as stable-route]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ^:private users ["u0" "u1" "u2"])
(def ^:private unmentioned "zz")
(def ^:private wildcard "*")
(def ^:private listed-users (conj users unmentioned))

(def ^:private wildcard-relations
  "The user relations that declare `user:*`. `owner` stays concrete."
  #{:reader :deleter :eligible :blocked})

(def ^:private base-permissions
  {:readable [:union [:relation :reader] [:arrow :parent :readable]]
   :granted [:union [:relation :deleter] [:arrow :parent :granted]]})

(def ^:private operator-templates
  "Operators over the recursive union-only operands, unions at an operator's
  root, and an arrow to an operator permission."
  {:removable [:intersection [:self :granted] [:self :readable]]
   :removable_top [:union [:relation :deleter]
                   [:intersection [:self :granted] [:self :readable]]]
   :prunable [:exclusion [:self :granted] [:self :readable]]
   :either [:intersection [:union [:self :granted] [:self :readable]]
            [:union [:relation :reader] [:relation :deleter] [:relation :eligible]]]
   :gated [:intersection [:relation :eligible] [:self :readable]]
   :safe [:exclusion [:self :readable] [:relation :blocked]]
   :parent_removable [:union [:relation :owner] [:arrow :parent :removable]]})

(def ^:private guarded-templates
  "Members that recurse through a linear guard."
  [[:union [:relation :reader]
    [:intersection [:arrow :parent :guarded] [:relation :eligible]]]
   [:union [:relation :reader]
    [:exclusion [:arrow :parent :guarded] [:relation :blocked]]]
   [:union [:relation :owner] [:relation :reader]
    [:exclusion [:intersection [:arrow :parent :guarded] [:relation :eligible]]
     [:relation :blocked]]]])

(def ^:private consulting-templates
  "Members whose guards or witnesses consult another permission."
  {:kept [:union [:relation :reader] [:exclusion [:arrow :parent :kept] [:self :banned]]]
   :reach [:union [:self :banned] [:arrow :link :reach]]
   :backed [:union [:relation :reader] [:intersection [:arrow :parent :backed] [:self :banned]]]
   :unread [:union [:relation :reader] [:exclusion [:arrow :parent :unread] [:self :readable]]]
   :vouched [:union [:relation :owner]
             [:intersection [:arrow :link :vouched] [:arrow :parent :granted]]]})

(def ^:private banned
  [:union [:relation :owner] [:exclusion [:arrow :parent :banned] [:relation :blocked]]])

(def ^:private tangled
  "Recursion through two operands: not linearly guarded."
  [:union [:relation :reader]
   [:intersection [:arrow :parent :tangled] [:arrow :link :tangled]]])

(defn- random-permissions [state]
  (let [operators (into {} (filter (fn [_] (rng/chance? state 55))) operator-templates)
        operators (cond-> (if (seq operators) operators (select-keys operator-templates [:removable]))
                    (contains? operators :parent_removable)
                    (assoc :removable (:removable operator-templates)))
        consulting (into {} (filter (fn [_] (rng/chance? state 40))) consulting-templates)]
    (cond-> (merge base-permissions operators
                   {:guarded (rng/pick state guarded-templates)}
                   consulting)
      (seq consulting) (assoc :banned banned)
      (rng/chance? state 40) (assoc :tangled tangled))))

(declare render-expression)

(defn- render-operand [expression]
  (if (contains? #{:union :intersection :exclusion} (first expression))
    (str "(" (render-expression expression) ")")
    (render-expression expression)))

(defn- render-expression [[kind a b :as expression]]
  (case kind
    :relation (name a)
    :self (name a)
    :arrow (str (name a) "->" (name b))
    :union (str/join " + " (map render-operand (rest expression)))
    :intersection (str (render-operand a) " & " (render-operand b))
    :exclusion (str (render-operand a) " - " (render-operand b))))

(defn- render-schema [permissions caveats?]
  (str (when caveats? "caveat enabled(flag bool) { flag }\n")
       "definition user {}\n"
       "definition folder {\n"
       "  relation parent: folder\n"
       "  relation link: folder\n"
       "  relation reader: user | user:*" (when caveats? " | user:* with enabled") "\n"
       "  relation owner: user\n"
       "  relation deleter: user | user:*\n"
       "  relation eligible: user | user:*\n"
       "  relation blocked: user | user:*" (when caveats? " | user with enabled") "\n"
       (apply str (for [[permission body] (sort-by key permissions)]
                    (str "  permission " (name permission) " = "
                         (render-expression body) "\n")))
       "}\n"))

;; ---------------------------------------------------------------------------
;; Relationships
;; ---------------------------------------------------------------------------

(def ^:private t0 1790000000000)
(def ^:private evaluation-times
  "Before any random expiry, between the two expiry bands, and after both."
  [t0 (+ t0 10) (+ t0 40)])

(defn- random-expiry
  "No expiry, an already expired one, or one inside either later band."
  [state]
  (case (rng/next-int! state 9)
    0 (- t0 1 (rng/next-int! state 5))
    (1 2) (+ t0 1 (rng/next-int! state 9))
    (3 4) (+ t0 11 (rng/next-int! state 29))
    nil))

(defn- caveatable?
  "Whether the schema lets this subject hold `relation` with `enabled`."
  [caveats? subject relation]
  (and caveats?
       (or (and (= :reader relation) (= wildcard subject))
           (and (= :blocked relation) (not= wildcard subject)))))

(defn- random-relationship
  [state caveats? [_ subject relation _ :as key]]
  [key (cond-> {}
         (rng/chance? state 45) (assoc :valid-until-ms (random-expiry state))
         (and (caveatable? caveats? subject relation) (rng/chance? state 50))
         (assoc :caveat? true))])

(defn- random-relationships
  "`{[subject-type subject relation folder] {:valid-until-ms t :caveat? b}}`."
  [state caveats? folders]
  (into {}
        (comp (map #(random-relationship state caveats? %))
              ;; An expiry drawn as nil keeps the relationship permanent.
              (map (fn [[key qualifier]]
                     [key (into {} (remove (comp nil? val)) qualifier)])))
        (concat
         (for [child folders parent folders :when (rng/chance? state 22)]
           [:folder parent :parent child])
         (for [child folders other folders :when (rng/chance? state 14)]
           [:folder other :link child])
         (for [folder folders
               subject (conj users wildcard)
               relation [:reader :owner :deleter :eligible :blocked]
               :when (and (or (not= wildcard subject) (contains? wildcard-relations relation))
                          (rng/chance? state (if (= wildcard subject) 22 26)))]
           [:user subject relation folder]))))

;; ---------------------------------------------------------------------------
;; Reference semantics
;; ---------------------------------------------------------------------------

(defn- live-keys
  "The relationship keys in force at `time` with Caveat flag `flag`."
  [relationships time flag]
  (into #{}
        (keep (fn [[key {:keys [valid-until-ms caveat?]}]]
                (when (and (or (nil? valid-until-ms) (< time valid-until-ms))
                           (or (not caveat?) flag))
                  key)))
        relationships))

(defn- member? [live subject relation folder]
  (or (contains? live [:user subject relation folder])
      (and (not= wildcard subject)
           (contains? wildcard-relations relation)
           (contains? live [:user wildcard relation folder]))))

(defn- intermediates [live via folder]
  (for [[subject-type subject relation resource] live
        :when (and (= :folder subject-type) (= via relation) (= folder resource))]
    subject))

(defn- references [expression]
  (case (first expression)
    (:union :intersection :exclusion) (into #{} (mapcat references) (rest expression))
    :self #{(second expression)}
    :arrow #{(nth expression 2)}
    #{}))

(defn- reach [dependencies permission]
  (loop [pending (vec (get dependencies permission)) seen #{}]
    (if-let [current (peek pending)]
      (if (contains? seen current)
        (recur (pop pending) seen)
        (recur (into (pop pending) (get dependencies current)) (conj seen current)))
      seen)))

(defn- denotation
  "`{permission #{folder}}` for `subject`: each strongly connected group of
  permissions iterates from empty once the permissions it names outside the
  group are complete."
  [permissions live folders subject]
  (let [dependencies (update-vals permissions references)
        holds? (fn holds? [values folder expression]
                 (let [[kind a b] expression]
                   (case kind
                     :relation (member? live subject a folder)
                     :self (contains? (get values a) folder)
                     :arrow (boolean (some #(contains? (get values b) %)
                                           (intermediates live a folder)))
                     :union (boolean (some #(holds? values folder %) (rest expression)))
                     :intersection (and (holds? values folder a) (holds? values folder b))
                     :exclusion (and (holds? values folder a) (not (holds? values folder b))))))
        group (fn [permission]
                (conj (set (filter #(contains? (reach dependencies %) permission)
                                   (reach dependencies permission)))
                      permission))
        groups (distinct (map group (keys permissions)))]
    (loop [values {} pending groups]
      (if (empty? pending)
        values
        (let [ready (first (filter (fn [members]
                                     (every? #(or (contains? members %) (contains? values %))
                                             (mapcat dependencies members)))
                                   pending))
              step (fn [values]
                     (reduce (fn [values permission]
                               (assoc values permission
                                      (set (filter #(holds? values % (get permissions permission))
                                                   folders))))
                             values ready))
              values (loop [values (reduce #(assoc %1 %2 #{}) values ready)]
                       (let [next (step values)]
                         (if (= next values) values (recur next))))]
          (recur values (remove #{ready} pending)))))))

(defn- reference
  "`{subject {permission #{folder}}}` for every listed user and the wildcard."
  [permissions relationships folders time flag]
  (let [live (live-keys relationships time flag)]
    (into {} (for [subject (conj listed-users wildcard)]
               [subject (denotation permissions live folders subject)]))))

;; ---------------------------------------------------------------------------
;; The client
;; ---------------------------------------------------------------------------

(defn- relationship [[subject-type subject relation folder] {:keys [valid-until-ms caveat?]}]
  (cond-> (eacl/->Relationship (eacl/spice-object subject-type subject) relation
                               (eacl/spice-object :folder folder))
    valid-until-ms (assoc :valid-until-ms valid-until-ms)
    caveat? (assoc :caveat "enabled")))

(defn- write! [client operation entries]
  (when (seq entries)
    (eacl/write-relationships!
     client (mapv (fn [[key qualifier]]
                    {:operation operation
                     ;; A delete names the endpoint triple only.
                     :relationship (relationship key (when (= :create operation) qualifier))})
                  entries))))

(defn- walk [lookup query page-size]
  (loop [after nil items [] pages 0]
    (let [page (lookup (cond-> (assoc query :first page-size) after (assoc :after after)))
          items (into items (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< pages 500))
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

(defn- divergence
  "Nil when every public answer of `client` at the current time equals
  `expected`, else the first difference."
  [client permissions folders expected context]
  (let [query #(cond-> % context (assoc :caveat-context context))
        user #(eacl/spice-object :user %)
        folder #(eacl/spice-object :folder %)
        holds? (fn [subject permission id]
                 (contains? (get-in expected [subject permission]) id))]
    (or
     ;; Checks, one at a time and batched.
     (first
      (for [permission permissions
            subject listed-users
            id folders
            :let [result (eacl/check-permission
                          client (query {:subject (user subject) :permission permission
                                         :resource (folder id)}))
                  expected (if (holds? subject permission id) :has-permission :no-permission)]
            :when (not= expected (:permissionship result))]
        {:check [subject permission id] :expected expected :actual (:permissionship result)}))
     (first
      (for [permission permissions
            :let [checks (vec (for [subject listed-users id folders]
                                {:subject (user subject) :permission permission
                                 :resource (folder id)}))
                  actual (mapv :allowed? (eacl/check-permissions client (query {:checks checks})))
                  wanted (vec (for [subject listed-users id folders]
                                (holds? subject permission id)))]
            :when (not= wanted actual)]
        {:batched-checks permission :expected wanted :actual actual}))
     ;; Resource walks and counts.
     (first
      (for [permission permissions
            subject listed-users
            page-size [1 3]
            :let [ids (mapv :id (walk #(eacl/lookup-resources client %)
                                      (query {:subject (user subject) :permission permission
                                              :resource/type :folder})
                                      page-size))
                  wanted (get-in expected [subject permission])]
            :when (not (and (= (count ids) (count (set ids))) (= wanted (set ids))))]
        {:lookup-resources [subject permission page-size] :expected wanted :actual ids}))
     (first
      (for [permission permissions
            subject listed-users
            :let [n (:count (eacl/count-resources
                             client (query {:subject (user subject) :permission permission
                                            :resource/type :folder})))
                  wanted (count (get-in expected [subject permission]))]
            :when (not= wanted n)]
        {:count-resources [subject permission] :expected wanted :actual n}))
     ;; Subject walks and counts.
     (first
      (for [permission permissions
            id folders
            :let [entries (walk #(eacl/lookup-subjects client %)
                                (query {:resource (folder id) :permission permission
                                        :subject/type :user})
                                2)
                  ids (mapv :id entries)
                  wanted (set (filter #(holds? % permission id) listed-users))
                  wildcard? (holds? wildcard permission id)
                  n (:count (eacl/count-subjects
                             client (query {:resource (folder id) :permission permission
                                            :subject/type :user})))
                  problem (cond
                            (not= wanted (granted-by-listing entries)) :listing
                            (not= wildcard? (contains? (set ids) wildcard)) :wildcard-entry
                            (not (every? #(or (= wildcard %) (contains? wanted %)) ids)) :ungranted-entry
                            (not= (count ids) (count (distinct ids))) :repeated-entry
                            (not= (count entries) n) :count-subjects)]
            :when problem]
        {:lookup-subjects [permission id] :problem problem :expected wanted
         :wildcard? wildcard? :actual entries :count n})))))

(defn- membership-reuse [stats] (:reused @stats 0))

(defn run-case
  "Runs one seeded case; returns its counters, with `:failure` on the first
  divergence."
  [seed]
  (let [state (atom seed)
        caveats? (rng/chance? state 50)
        permissions (random-permissions state)
        schema (render-schema permissions caveats?)
        folders (mapv #(str "f" %) (range (+ 4 (rng/next-int! state 4))))
        relationships (random-relationships state caveats? folders)
        conn (datascript/create-conn)
        now (atom t0)
        options {:clock #(deref now)
                 :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0))}
        names (vec (sort (keys permissions)))
        flags (if caveats? [true false] [nil])
        stats (atom {})
        check! (fn [client relationships time]
                 (reset! now time)
                 (some (fn [flag]
                         (when-let [difference
                                    (divergence client names folders
                                                (reference permissions relationships folders
                                                           time (if caveats? flag true))
                                                (when caveats? {"flag" flag}))]
                           (assoc difference :time time :flag flag)))
                       flags))
        failure (fn [difference relationships]
                  {:failure (assoc difference :seed seed :schema schema
                                   :relationships relationships)})]
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users [unmentioned] folders)))
    (let [writer (datascript/make-client conn options)]
      (eacl/write-schema! writer schema)
      (write! writer :create relationships))
    (binding [stable-route/*membership-stats* stats]
      (let [client (datascript/make-client conn options)
            before (some (fn [time] (check! client relationships time))
                         (take 2 evaluation-times))]
        (if before
          (failure before relationships)
          ;; Continue on a client restored from this one's exported cache.
          (let [bounds {:max-entries 4096}
                snapshot (datascript/export-cache-snapshot client bounds)
                client (datascript/make-client conn options)
                _ (datascript/restore-cache-snapshot! client snapshot bounds)
                time (last evaluation-times)]
            (if-let [difference (check! client relationships time)]
              (failure difference relationships)
              ;; Replace or delete some relationships, wildcard ones among
              ;; them, then grant new ones. Cached answers must follow.
              (let [existing (vec (sort (keys relationships)))
                    deleted (into {} (filter (fn [_] (rng/chance? state 20)))
                                  (select-keys relationships existing))
                    candidates (vec (for [id folders
                                          subject (conj users wildcard)
                                          relation [:reader :deleter :eligible :blocked]
                                          :let [key [:user subject relation id]]
                                          :when (not (contains? relationships key))]
                                      key))
                    created (into {}
                                  (comp (filter (fn [_] (rng/chance? state 12)))
                                        (map (fn [key]
                                               [key (cond-> {}
                                                      (rng/chance? state 30)
                                                      (assoc :valid-until-ms (+ time 5 (rng/next-int! state 20)))
                                                      (and (caveatable? caveats? (second key) (nth key 2))
                                                           (rng/chance? state 50))
                                                      (assoc :caveat? true))])))
                                  candidates)
                    updated (merge (apply dissoc relationships (keys deleted)) created)]
                (write! client :delete deleted)
                (write! client :create created)
                (if-let [difference (or (check! client updated time)
                                        (check! client updated (+ time 30)))]
                  (failure (assoc difference :deleted (vec (keys deleted)) :created created)
                           updated)
                  {:cases 1
                   :wildcard-relationships (count (filter #(= wildcard (second %)) (keys updated)))
                   :writes (+ (count deleted) (count created))
                   :reused (membership-reuse stats)})))))))))

(defn run-campaign
  "Runs cases seeded `first-seed`..; stops at the first divergence, returned
  under `:failure`."
  [first-seed cases]
  (reduce (fn [totals seed]
            (let [result (run-case seed)]
              (if-let [failure (:failure result)]
                (reduced (assoc totals :failure failure))
                (merge-with + totals result))))
          {:cases 0}
          (range first-seed (+ first-seed cases))))

(deftest wildcard-answers-follow-the-reference-through-every-operator-route-test
  (let [report (run-campaign 1 #?(:clj 40 :cljs 4))]
    (is (nil? (:failure report)) (pr-str (:failure report)))
    (is (pos? (:wildcard-relationships report)))
    (is (pos? (:writes report)))
    (is (pos? (:reused report)) "later requests reuse cached membership decisions")))
