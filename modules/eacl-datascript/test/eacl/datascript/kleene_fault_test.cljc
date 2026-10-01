(ns eacl.datascript.kleene-fault-test
  "Strong-Kleene fault semantics through the public API. A definite grant or
  denial absorbs a faulting branch on every route, a fault never stops
  evaluation, lookups and counts fail only when a consumed candidate's
  decision faults, and every answer is a function of the schema, the data,
  the time and the request context alone: independent of entity order,
  schema text order, route, cache state and page size.

  Faults here come from `bad(m map<bool>) { m[\"x\"] }` evaluated with
  `{\"m\" {}}`: a missing map key, which only evaluation can detect, so the
  request passes fail-fast context admission."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.memoized-membership-refinement-test :as random]))

(def ^:private faulting {"m" {}})
(def ^:private bad-caveat "caveat bad(m map<bool>) { m[\"x\"] }")

(defn- outcome
  "A comparable public outcome: the value's semantic part, or the error type.
  Page members compare as a sorted vector: result order follows entity ids by
  contract and is not part of the decision. Detailed items name their object."
  [f]
  (try
    (let [result (f)]
      (cond
        (contains? result :data)
        [:page (vec (sort (map #(or (get-in % [:object :id]) (:id %)) (:data result))))]
        (contains? result :count) [:count (:count result)]
        (contains? result :permissionship) [:check (:permissionship result)]
        (vector? result) [:batch (mapv :permissionship result)]
        :else [:value result]))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      [:error (:type (ex-data error))])))

(defn- rows-outcome
  "The number of relationship rows an authorized read keeps, or the error."
  [f]
  (try [:rows (count (:data (f)))]
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
         [:error (:type (ex-data error))])))

(def ^:private failure [:error :eacl.authorization/evaluation-failure])

(defn- client-with [schema objects relationships]
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock (constantly 1000)})]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) objects))
    (eacl/create-relationships! client relationships)
    client))

(defn- user [id] (eacl/spice-object :user id))
(defn- doc [id] (eacl/spice-object :doc id))
(defn- folder [id] (eacl/spice-object :folder id))

(defn- grant [subject relation resource]
  (eacl/->Relationship subject relation resource))

(defn- faulting-grant [subject relation resource]
  (assoc (eacl/->Relationship subject relation resource) :caveat "bad"))

(defn- every-route
  "The public outcome of one permission for alice on `resource`, through
  every authorization operation, with and without the answer cache."
  [client permission resource resource-type]
  (vec
   (for [cache? [true false true]]
     (let [request {:subject (user "alice") :permission permission
                    :caveat-context faulting :cache? cache?}]
       {:check (outcome #(eacl/check-permission client (assoc request :resource resource)))
        :can? (eacl/can? client (assoc request :resource resource))
        :batch (outcome #(eacl/check-permissions
                          client {:caveat-context faulting :cache? cache?
                                  :checks [(-> request (dissoc :caveat-context :cache?)
                                               (assoc :resource resource))]}))
        :lookup (outcome #(eacl/lookup-resources client (assoc request :resource/type resource-type :first 10)))
        :count (outcome #(eacl/count-resources client (assoc request :resource/type resource-type)))
        :subjects (outcome #(eacl/lookup-subjects
                             client {:resource resource :permission permission :subject/type :user
                                     :caveat-context faulting :cache? cache? :first 10}))
        :authorized-read
        (rows-outcome #(eacl/read-relationships
                   client {:resource/type resource-type :resource/id (:id resource)
                           :subject/type :user :caveat-context faulting :cache? cache?
                           :authorization {:subject (user "alice") :permission permission
                                           :on :resource}
                           :first 10}))}))))

(def ^:private absorber-shapes
  "One faulting operand beside a definite one, through each connective.
  `:absorbed` builds the data with the definite absorber present (a grant
  for a union, an absent operand for an intersection or an exclusion's left
  side, a grant for an exclusion's right side) and names the decision it
  forces; without it the fault is the decision."
  [{:permission :view :body ["reader" "editor"] :op " + " :rows 2
    :data (fn [absorbed?] (cond-> [(faulting-grant (user "alice") :reader (doc "d"))]
                            absorbed? (conj (grant (user "alice") :editor (doc "d")))))
    :absorbed {:check :has-permission :lookup ["d"]}}
   {:permission :view :body ["parent->reader" "parent->editor"] :op " + " :rows 0
    :data (fn [absorbed?] (cond-> [(grant (doc "p") :parent (doc "d"))
                                   (faulting-grant (user "alice") :reader (doc "p"))]
                            absorbed? (conj (grant (user "alice") :editor (doc "p")))))
    :absorbed {:check :has-permission :lookup ["d"]}}
   {:permission :view :body ["reader" "parent->view"] :op " + " :rows 1
    :data (fn [absorbed?] (cond-> [(faulting-grant (user "alice") :reader (doc "d"))
                                   (grant (doc "p") :parent (doc "d"))]
                            absorbed? (conj (grant (user "alice") :reader (doc "p")))))
    :absorbed {:check :has-permission :lookup ["d" "p"]}}
   {:permission :both :body ["reader" "editor"] :op " & " :rows 1
    :data (fn [absorbed?] (cond-> [(faulting-grant (user "alice") :reader (doc "d"))]
                            (not absorbed?) (conj (grant (user "alice") :editor (doc "d")))))
    :absorbed {:check :no-permission :lookup []}}
   {:permission :open :body ["editor" "reader"] :op " - " :ordered? true :rows 1
    :data (fn [absorbed?] (cond-> [(faulting-grant (user "alice") :reader (doc "d"))]
                            (not absorbed?) (conj (grant (user "alice") :editor (doc "d")))))
    :absorbed {:check :no-permission :lookup []}}
   {:permission :open :body ["reader" "editor"] :op " - " :ordered? true :rows 2
    :data (fn [absorbed?] (cond-> [(faulting-grant (user "alice") :reader (doc "d"))]
                            absorbed? (conj (grant (user "alice") :editor (doc "d")))))
    :absorbed {:check :no-permission :lookup []}}])

(defn- render-absorber-schema [{:keys [permission body op ordered?]} flip? relations-flipped?]
  (let [body (if (and flip? (not ordered?)) (vec (reverse body)) body)
        relations ["relation reader: user | user with bad"
                   "relation editor: user"
                   "relation parent: doc"]
        relations (if relations-flipped? (reverse relations) relations)]
    (str bad-caveat "\n definition user {}\n definition doc {\n "
         (str/join "\n " relations)
         "\n permission " (name permission) " = " (str/join op body)
         "\n}")))

(deftest a-definite-operand-absorbs-a-faulting-one-on-every-route-in-any-order
  ;; NEW-1 (eacl-rust spec 10 §6.1): the outcome of `a + b` with one faulting
  ;; and one granting branch used to depend on entity, rule and route order.
  (doseq [{:keys [permission body data absorbed rows] :as shape} absorber-shapes
          absorbed? [false true]
          flip? [false true]
          relations-flipped? [false true]
          objects [["alice" "d" "p"] ["p" "d" "alice"]]
          relationship-order [identity reverse]]
    (testing (pr-str [permission body absorbed? flip? relations-flipped? objects])
      (let [client (client-with (render-absorber-schema shape flip? relations-flipped?)
                                objects (vec (relationship-order (data absorbed?))))
            routes (every-route client permission (doc "d") :doc)
            expected
            (if absorbed?
              (let [has? (= :has-permission (:check absorbed))]
                {:check [:check (:check absorbed)] :can? has?
                 :batch [:batch [(:check absorbed)]]
                 :lookup [:page (:lookup absorbed)] :count [:count (count (:lookup absorbed))]
                 :subjects [:page (if has? ["alice"] [])]
                 :authorized-read [:rows (if has? rows 0)]})
              {:check failure :can? false :batch failure :lookup failure :count failure
               :subjects failure :authorized-read (if (pos? rows) failure [:rows 0])})]
        (doseq [route routes]
          (is (= expected route)))))))

(defn- demand-fixture [body cache?]
  (let [client (client-with
                (str bad-caveat "
                 definition user {}
                 definition folder {
                   relation parent: folder
                   relation reader: user | user with bad
                   relation eligible: user
                   permission base = reader
                   permission readable = " body "
                   permission access = readable & eligible
                 }")
                ["alice" "low" "high" "child"]
                [(faulting-grant (user "alice") :reader (folder "low"))
                 (grant (user "alice") :reader (folder "high"))
                 (grant (folder "low") :parent (folder "child"))
                 (grant (folder "high") :parent (folder "child"))
                 (grant (user "alice") :eligible (folder "child"))])
        request {:subject (user "alice") :permission :access :cache? cache? :caveat-context faulting}]
    {:point #(eacl/check-permission client (assoc request :resource (folder "child")))
     :lookup #(eacl/lookup-resources client (assoc request :resource/type :folder :first 100))
     :count #(eacl/count-resources client (assoc request :resource/type :folder))}))

(deftest a-later-plain-grant-absorbs-an-earlier-faulting-arrow-target
  ;; PR #209 pinned the earlier faulting target as authoritative. Under
  ;; strong Kleene the plain grant through `high` decides `readable`, so
  ;; `access` holds, whatever order evaluation visits the targets in.
  (doseq [body ["parent->reader + parent->readable"
                "parent->base + parent->readable"
                "parent->reader + (parent->readable & eligible)"]
          cache? [false true]]
    (testing (str body ", cache=" cache?)
      (let [{:keys [point lookup count]} (demand-fixture body cache?)]
        (doseq [_ (range 2)]
          (is (= [:check :has-permission] (outcome point)))
          (is (= [:page ["child"]] (outcome lookup)))
          (is (= [:count 1] (outcome count))))))))

(defn- corrupt-expiration-store [body damaged-relation exclusion?]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:clock (constantly 100)})
        alice (user "alice")]
    (eacl/write-schema!
     client
     (str "definition user {} definition folder {
             relation parent: folder
             relation reader: user
             relation eligible: user
             permission readable = " body "
             permission access = " (if exclusion? "eligible - readable" "readable & eligible") "
           }"))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "low" "high" "child"]))
    (eacl/create-relationships!
     client
     (mapv (fn [relationship]
             (cond-> relationship
               (and (= damaged-relation (:relation relationship))
                    (or (= "low" (get-in relationship [:subject :id]))
                        (= "low" (get-in relationship [:resource :id]))))
               (assoc :valid-until-ms 1000)))
           [(grant alice :reader (folder "low"))
            (grant alice :reader (folder "high"))
            (grant (folder "low") :parent (folder "child"))
            (grant (folder "high") :parent (folder "child"))
            (grant alice :eligible (folder "child"))]))
    (let [qid (ds/q '[:find ?e . :where [?e :eacl.relationship-qualifier/valid-until-ms 1000]]
                    (ds/db conn))]
      (ds/transact! conn [[:db/add qid :unknown/private "invalid qualifier"]]))
    {:client client :alice alice :child (folder "child")}))

(deftest a-malformed-expiration-qualifier-is-absorbed-like-any-fault
  ;; These schemas declare no Caveats; a damaged expiration qualifier is a
  ;; qualifier fault. The plain path through `high` decides `readable`.
  (doseq [body ["parent->reader + parent->readable"
                "parent->reader + (parent->readable & eligible)"]
          damaged-relation [:reader :parent]
          exclusion? [false true]
          cache? [false true]]
    (testing (pr-str [body damaged-relation exclusion? cache?])
      (let [{:keys [client alice child]} (corrupt-expiration-store body damaged-relation exclusion?)
            request {:subject alice :permission :access :cache? cache?}
            expected (if exclusion? :no-permission :has-permission)]
        (doseq [_ (range 2)]
          (is (= [:check expected]
                 (outcome #(eacl/check-permission client (assoc request :resource child)))))
          (is (= [:page (if exclusion? [] ["child"])]
                 (outcome #(eacl/lookup-resources client (assoc request :resource/type :folder :first 100)))))
          (is (= [:count (if exclusion? 0 1)]
                 (outcome #(eacl/count-resources client (assoc request :resource/type :folder))))))))))

(deftest an-unrelated-direct-grant-decides-beside-a-damaged-qualifier
  (let [{:keys [client alice child]}
        (corrupt-expiration-store "reader + parent->reader + (parent->readable & eligible)"
                                  :parent false)]
    (eacl/create-relationship! client (grant alice :reader child))
    (doseq [cache? [false true]]
      (let [request {:subject alice :permission :access :cache? cache?}]
        (is (:allowed? (eacl/check-permission client (assoc request :resource child))))
        (is (= [child] (:data (eacl/lookup-resources client (assoc request :resource/type :folder :first 100)))))
        (is (= 1 (:count (eacl/count-resources client (assoc request :resource/type :folder)))))))))

(def ^:private unreachable-fault-schemas
  "EACL-RS-007: the only qualified edge faults and leads to no resource."
  {:acyclic "definition project {}
             definition group {
               relation member: project with bad
               permission share = member
             }
             definition org {
               relation manager: group
               permission share = manager->share
             }"
   :recursive "definition project {}
               definition group {
                 relation member: project with bad
                 permission share = member
               }
               definition org {
                 relation reader: org
                 relation manager: group
                 permission share = manager->share + reader->share
               }"})

(deftest a-faulting-edge-that-reaches-no-resource-never-fails-a-walk
  ;; EACL-RS-007: reverse walks qualified each released row and threw before
  ;; learning whether it led to any resource, while every point check
  ;; answered no-permission. Both enumeration routes now agree with checks.
  (doseq [[shape schema] unreachable-fault-schemas
          cache? [false true]]
    (testing (pr-str shape cache?)
      (let [client (client-with (str bad-caveat "\n" schema) ["a" "r" "o"]
                                [(assoc (eacl/->Relationship (eacl/spice-object :project "a") :member
                                                             (eacl/spice-object :group "r"))
                                        :caveat "bad")])
            request {:subject (eacl/spice-object :project "a") :permission :share
                     :caveat-context faulting :cache? cache?}]
        (is (= [:page []] (outcome #(eacl/lookup-resources client (assoc request :resource/type :org :first 1000)))))
        (is (= [:count 0] (outcome #(eacl/count-resources client (assoc request :resource/type :org)))))
        (is (= [:check :no-permission]
               (outcome #(eacl/check-permission client (assoc request :resource (eacl/spice-object :org "o"))))))
        ;; The faulting edge itself is the decision for the group.
        (is (= failure (outcome #(eacl/check-permission
                                  client (assoc request :resource (eacl/spice-object :group "r"))))))
        (is (= failure (outcome #(eacl/lookup-resources client (assoc request :resource/type :group :first 10)))))))))

(deftest a-filter-edge-composes-with-every-possible-decision
  ;; EACL-RS-008: a `:definite` filtered lookup dropped a conditional
  ;; candidate before its faulting filter edge was consulted, while
  ;; `:detailed` failed. The candidate's value is `decision & filter edge`
  ;; under either policy.
  (let [client (client-with
                (str bad-caveat "
                 caveat c2(role string) { role != \"b\" }
                 definition user {}
                 definition doc {
                   relation viewer: user with c2
                   relation owner: user with bad
                   permission view = viewer
                 }")
                ["u" "o" "d"]
                [(assoc (grant (user "u") :viewer (doc "d")) :caveat "c2")
                 (assoc (grant (user "o") :owner (doc "d")) :caveat "bad")])
        lookup (fn [context & {:as more}]
                 (outcome #(eacl/lookup-resources
                            client (merge {:subject (user "u") :permission :view :resource/type :doc
                                           :caveat-context context :first 10
                                           :resource/relationship {:relation :owner :subject (user "o")}}
                                          more))))]
    (doseq [cache? [false true]]
      (is (= failure (lookup faulting :cache? cache?)))
      (is (= failure (lookup faulting :cache? cache? :result-policy :detailed)))
      (is (= failure (lookup (assoc faulting "role" "a") :cache? cache?)))
      ;; A definite denial absorbs the faulting filter edge.
      (is (= [:page []] (lookup (assoc faulting "role" "b") :cache? cache?))))
    (is (= [:check :conditional-permission]
           (outcome #(eacl/check-permission client {:subject (user "u") :permission :view
                                                    :resource (doc "d") :caveat-context faulting}))))))

(deftest only-a-consumed-candidate-can-fail-a-page
  ;; Candidates are consumed in order up to the page's sentinel. A faulting
  ;; candidate after the sentinel is never consumed by that page; the page
  ;; that consumes it fails, and so do a full count and a long page.
  (let [client (client-with
                (str bad-caveat "
                 definition user {}
                 definition doc {
                   relation reader: user | user with bad
                   permission view = reader
                 }")
                ["alice" "d1" "d2" "d3"]
                [(grant (user "alice") :reader (doc "d1"))
                 (grant (user "alice") :reader (doc "d2"))
                 (faulting-grant (user "alice") :reader (doc "d3"))])
        request {:subject (user "alice") :permission :view :resource/type :doc
                 :caveat-context faulting}
        first-page (eacl/lookup-resources client (assoc request :first 1))]
    (is (= ["d1"] (mapv :id (:data first-page))))
    (is (= failure (outcome #(eacl/lookup-resources
                              client (assoc request :first 1
                                            :after (get-in first-page [:page-info :end-cursor]))))))
    (is (= failure (outcome #(eacl/lookup-resources client (assoc request :first 10)))))
    (is (= failure (outcome #(eacl/count-resources client (dissoc request :first)))))
    (is (= [:count 1] (outcome #(eacl/count-resources client (assoc request :count-limit 1)))))))

(deftest a-conditional-residual-and-a-fault-compose-pointwise
  ;; `view = reader + editor`: reader is conditional on `role`, editor
  ;; faults. In the completion where reader is false the union faults, so
  ;; the decision fails; once `role` decides reader true, the grant absorbs
  ;; the fault on every route.
  (let [client (client-with
                (str bad-caveat "
                 caveat staff(role string) { role == \"a\" }
                 definition user {}
                 definition doc {
                   relation reader: user with staff
                   relation editor: user with bad
                   permission view = reader + editor
                 }")
                ["alice" "d"]
                [(assoc (grant (user "alice") :reader (doc "d")) :caveat "staff")
                 (faulting-grant (user "alice") :editor (doc "d"))])
        routes (fn [context]
                 (let [request {:subject (user "alice") :permission :view :caveat-context context}]
                   [(outcome #(eacl/check-permission client (assoc request :resource (doc "d"))))
                    (outcome #(eacl/lookup-resources client (assoc request :resource/type :doc :first 10
                                                                   :result-policy :detailed)))
                    (outcome #(eacl/count-resources client (assoc request :resource/type :doc
                                                                  :result-policy :detailed)))]))]
    (is (= [failure failure failure] (routes faulting)))
    (is (= [[:check :has-permission] [:page ["d"]] [:count 1]] (routes (assoc faulting "role" "a"))))
    (is (= [failure failure failure] (routes (assoc faulting "role" "b"))))))

;; ---------------------------------------------------------------------------
;; Wildcard subjects
;; ---------------------------------------------------------------------------

(def ^:private wildcard-schema
  "A relation that declares `user:*`, read through each route: a relation
  (the union-only point route and least-path walks), a recursive union
  (first-discovery), an acyclic operator (the scalar and vector evaluators),
  recursion through two operands (the tabled evaluator), linearly guarded
  recursion (the guarded membership search) and an exclusion. Subject
  listings of the operators take the wildcard touch route."
  (str bad-caveat "
   definition user {}
   definition folder {
     relation parent: folder
     relation link: folder
     relation reader: user | user with bad | user:* | user:* with bad
     relation editor: user
     relation blocked: user | user with bad
     permission view = reader
     permission chain = reader + parent->chain
     permission edit = reader & editor
     permission tangled = reader + (parent->tangled & link->tangled)
     permission guarded = reader + (parent->guarded & editor)
     permission screened = reader - blocked
   }"))

(def ^:private wildcard-permissions [:view :chain :edit :tangled :guarded :screened])

(def ^:private own-and-wildcard-cases
  "Alice's own `reader` relationship on f1 and the wildcard's, each plain or
  faulting, beside her plain `editor`. `:alice` is her decision on every
  permission; `:entries` gives f1's subject listing per permission, or
  `:failure` when a consumed entry faults. The `*` entry's own decision uses
  the wildcard's relationships only, and `edit` also needs `editor`, which
  the wildcard lacks."
  [{:label "a definite own grant absorbs a faulting wildcard Caveat"
    :own grant :wildcard faulting-grant :alice :has-permission
    :entries #(if (= :edit %) ["alice"] :failure)}
   {:label "a definite wildcard grant absorbs a faulting own Caveat"
    :own faulting-grant :wildcard grant :alice :has-permission
    :entries #(if (= :edit %) ["alice"] ["*" "alice"])}
   {:label "faults on both sides fault"
    :own faulting-grant :wildcard faulting-grant :alice :failure
    :entries (constantly :failure)}])

(deftest a-definite-grant-through-own-or-wildcard-relationship-absorbs-a-fault-through-the-other
  ;; Membership in `reader` is the union of the subject's own relationship
  ;; and the wildcard's. The tabled evaluator always demands both, so a
  ;; faulting wildcard Caveat beside a definite own grant failed there while
  ;; the evaluators that stop at the own grant granted.
  (doseq [{:keys [label own wildcard alice entries]} own-and-wildcard-cases
          objects [["alice" "f1"] ["f1" "alice"]]
          relationship-order [identity reverse]
          permission wildcard-permissions]
    (testing (pr-str [label objects permission])
      (let [client (client-with wildcard-schema objects
                                (vec (relationship-order
                                      [(own (user "alice") :reader (folder "f1"))
                                       (wildcard (user "*") :reader (folder "f1"))
                                       (grant (user "alice") :editor (folder "f1"))])))
            listed (entries permission)
            expected
            (merge
             (if (= :has-permission alice)
               {:check [:check :has-permission] :can? true :batch [:batch [:has-permission]]
                :lookup [:page ["f1"]] :count [:count 1] :authorized-read [:rows 3]}
               {:check failure :can? false :batch failure :lookup failure :count failure
                :authorized-read failure})
             (if (= :failure listed)
               {:subjects failure :subject-count failure}
               {:subjects [:page listed] :subject-count [:count (count listed)]}))]
        (doseq [[route cache?] (map vector
                                    (every-route client permission (folder "f1") :folder)
                                    [true false true])]
          (is (= expected
                 (assoc route :subject-count
                        (outcome #(eacl/count-subjects
                                   client {:resource (folder "f1") :permission permission
                                           :subject/type :user :caveat-context faulting
                                           :cache? cache?}))))))))))

(defn- subject-walk
  "Each page of a `lookup-subjects` walk of `size` as `[[id excluded-ids]]`,
  ending at the first failure."
  [client query size]
  (loop [after nil pages []]
    (let [page (try (eacl/lookup-subjects client (cond-> (assoc query :first size)
                                                    after (assoc :after after)))
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                      (ex-data error)))]
      (if-let [type (:type page)]
        (conj pages [:error type])
        (let [pages (conj pages (mapv (fn [{:keys [id excluded-subjects]}]
                                        [id (set (map :id excluded-subjects))])
                                      (:data page)))]
          (if (and (get-in page [:page-info :has-next-page?]) (< (count pages) 10))
            (recur (get-in page [:page-info :end-cursor]) pages)
            pages))))))

(deftest a-faulting-subject-is-excluded-from-the-wildcard-and-fails-only-the-page-that-consumes-it
  ;; `screened = reader - zblocked` on f1: the wildcard, carol and dave read
  ;; plainly and bob's ban faults, so bob's decision is T - U = U. The `*`
  ;; entry excludes him, as it excludes every touched subject without a
  ;; definite grant, and only a page or count that consumes bob's own
  ;; candidate fails. The touch cover lists `zblocked`'s subjects after
  ;; `reader`'s, so bob is the walk's last candidate. The exclusion list used
  ;; to throw on his fault, failing the first page.
  (doseq [objects [["carol" "dave" "bob" "erin" "f1"] ["erin" "bob" "carol" "dave" "f1"]]
          cache? [true false true]]
    (testing (pr-str [objects cache?])
      (let [client (client-with
                    (str bad-caveat "
                     definition user {}
                     definition folder {
                       relation reader: user | user:*
                       relation zblocked: user | user with bad
                       permission screened = reader - zblocked
                     }")
                    objects
                    [(grant (user "*") :reader (folder "f1"))
                     (grant (user "carol") :reader (folder "f1"))
                     (grant (user "dave") :reader (folder "f1"))
                     (faulting-grant (user "bob") :zblocked (folder "f1"))])
            query {:resource (folder "f1") :permission :screened :subject/type :user
                   :caveat-context faulting :cache? cache?}
            check #(outcome (fn [] (eacl/check-permission
                                    client {:subject (user %) :permission :screened
                                            :resource (folder "f1") :caveat-context faulting
                                            :cache? cache?})))]
        (is (= [[["*" #{"bob"}]] [["carol" #{}]] failure] (subject-walk client query 1)))
        (is (= [[["*" #{"bob"}] ["carol" #{}]] failure] (subject-walk client query 2)))
        (is (= [failure] (subject-walk client query 10)))
        (is (= failure (outcome #(eacl/count-subjects client query))))
        (is (= [:count 1] (outcome #(eacl/count-subjects client (assoc query :count-limit 1)))))
        ;; erin touches nothing, so the wildcard alone decides for her.
        (is (= [failure [:check :has-permission] [:check :has-permission]]
               (mapv check ["bob" "carol" "erin"])))))))

;; ---------------------------------------------------------------------------
;; Order-independence campaign against an independent Kleene reference
;; ---------------------------------------------------------------------------

(def ^:private campaign-permissions
  "Each permission with its reference expression. Together they cover the
  acyclic and recursive union routes, acyclic operators, a delegated
  recursive operand and linearly guarded recursion."
  [[:near "reader + parent->reader" [:union [:rel :reader] [:arrow-rel :parent :reader]]]
   [:view "reader + parent->view" [:union [:rel :reader] [:arrow :parent :view]]]
   [:gated "near & eligible" [:inter [:perm :near] [:rel :eligible]]]
   [:open "near - banned" [:minus [:perm :near] [:rel :banned]]]
   [:walked "view & eligible" [:inter [:perm :view] [:rel :eligible]]]
   [:inherited "reader + (parent->inherited & eligible)"
    [:union [:rel :reader] [:inter [:arrow :parent :inherited] [:rel :eligible]]]]])

(defn- k-or [a b] (cond (or (= :T a) (= :T b)) :T (or (= :U a) (= :U b)) :U :else :F))
(defn- k-and [a b] (cond (or (= :F a) (= :F b)) :F (or (= :U a) (= :U b)) :U :else :T))
(defn- k-not [a] (case a :T :F :F :T :U))

(defn- reference
  "The least fixed point of every permission on every folder for one
  subject, over the per-edge values :T, :U and :F (strong Kleene, union
  accumulation from all-false)."
  [edges subject folders]
  (let [edge (fn [s relation r] (get edges [s relation r] :F))
        expressions (into {} (map (juxt first #(nth % 2))) campaign-permissions)
        evaluate
        (fn evaluate [values folder [kind a b]]
          (case kind
            :rel (edge subject a folder)
            :perm (get values [folder a] :F)
            :arrow-rel (reduce k-or :F (map #(k-and (edge % a folder) (edge subject b %)) folders))
            :arrow (reduce k-or :F (map #(k-and (edge % a folder) (get values [% b] :F)) folders))
            :union (k-or (evaluate values folder a) (evaluate values folder b))
            :inter (k-and (evaluate values folder a) (evaluate values folder b))
            :minus (k-and (evaluate values folder a) (k-not (evaluate values folder b)))))]
    (loop [values {}]
      (let [next (into {}
                       (for [folder folders [permission expression] expressions]
                         [[folder permission]
                          (k-or (get values [folder permission] :F)
                                (evaluate values folder expression))]))]
        (if (= next values) values (recur next))))))

(def ^:private campaign-users ["u0" "u1"])
(def ^:private campaign-folders ["f0" "f1" "f2" "f3"])

(defn- random-edges [state]
  (into {}
        (concat
         (for [p campaign-folders f campaign-folders
               :when (random/chance? state 30)]
           [[p :parent f] (if (random/chance? state 20) :U :T)])
         (for [u campaign-users relation [:reader :eligible :banned] f campaign-folders
               :let [value (random/pick state [:F :F :T :T :U])]
               :when (not= :F value)]
           [[u relation f] value]))))

(defn- campaign-schema [flip?]
  (let [render (fn [[permission body]]
                 (let [[a b] (str/split body #" \+ " 2)]
                   (str "permission " (name permission) " = "
                        (if (and flip? b (not (str/includes? body "&")) (not (str/includes? body "-")))
                          (str b " + " a)
                          body))))
        relations ["relation parent: folder | folder with bad"
                   "relation reader: user | user with bad"
                   "relation eligible: user | user with bad"
                   "relation banned: user | user with bad"]]
    (str bad-caveat "\n definition user {}\n definition folder {\n "
         (str/join "\n " (if flip? (reverse relations) relations))
         "\n "
         (str/join "\n " (map render (cond-> campaign-permissions flip? reverse)))
         "\n}")))

(defn- campaign-client [edges flip?]
  (client-with
   (campaign-schema flip?)
   (cond-> (into campaign-users campaign-folders) flip? reverse)
   (vec
    (cond->> (for [[[s relation r] value] (sort edges)]
               (let [subject (if (= :parent relation) (folder s) (user s))]
                 (cond-> (grant subject relation (folder r))
                   (= :U value) (assoc :caveat "bad"))))
      flip? reverse))))

(defn- public-answers
  "Every check, lookup and count of the campaign permissions, as comparable
  outcomes."
  [client cache?]
  (into {}
        (for [u campaign-users [permission] campaign-permissions
              :let [request {:subject (user u) :permission permission
                             :caveat-context faulting :cache? cache?}]
              [kind f] (concat
                        (for [f campaign-folders]
                          [[:check f] #(eacl/check-permission client (assoc request :resource (folder f)))])
                        [[:lookup #(eacl/lookup-resources client (assoc request :resource/type :folder :first 100))]
                         [:count #(eacl/count-resources client (assoc request :resource/type :folder))]])]
          [[u permission kind] (outcome f)])))

(defn- expected-answers [edges]
  (into {}
        (for [u campaign-users
              :let [values (reference edges u campaign-folders)]
              [permission] campaign-permissions
              :let [value-of #(get values [% permission] :F)
                    faulted? (some #(= :U (value-of %)) campaign-folders)
                    granted (filterv #(= :T (value-of %)) campaign-folders)]
              [kind answer] (concat
                             (for [f campaign-folders]
                               [[:check f] (case (value-of f)
                                             :T [:check :has-permission]
                                             :F [:check :no-permission]
                                             :U failure)])
                             [[:lookup (if faulted? failure [:page granted])]
                              [:count (if faulted? failure [:count (count granted)])]])]
          [[u permission kind] answer])))

(deftest answers-are-independent-of-entity-schema-and-route-order
  ;; Random folder graphs (cycles included) whose edges are plain, absent or
  ;; faulting. Each graph is loaded twice, with objects, relationships,
  ;; relation declarations, permissions and union operands in opposite
  ;; orders. Every check, lookup and count must equal the reference, cache
  ;; on and off.
  (let [state (atom 20261001)]
    (doseq [case-index (range 12)]
      (let [edges (random-edges state)
            expected (expected-answers edges)]
        (doseq [flip? [false true]
                :let [client (campaign-client edges flip?)]
                cache? [true false]]
          (let [actual (public-answers client cache?)
                diff (into {}
                           (keep (fn [[k v]] (when (not= v (get actual k)) [k {:expected v :actual (get actual k)}])))
                           expected)]
            (is (empty? diff) (pr-str {:case case-index :flip? flip? :cache? cache? :edges edges :diff diff}))))))))

(deftest an-absorbed-fault-is-observable-without-changing-the-decision
  ;; The only path crosses a faulting via edge whose target is absent: the
  ;; denial absorbs the fault. The decision keeps its public shape; the
  ;; request meters an `:io-observer` receives record the absorbed fault.
  (let [observed (atom [])
        conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock (constantly 1000)
                      :io-observer #(swap! observed conj %)})
        request {:subject (user "alice") :permission :view :resource (doc "d")
                 :caveat-context faulting :cache? false}
        meters (fn [] (:meters (peek @observed)))]
    (eacl/write-schema! client (str bad-caveat "
                                definition user {}
                                definition doc {
                                  relation parent: doc | doc with bad
                                  relation editor: user
                                  permission view = parent->editor
                                }"))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "d" "p"]))
    (eacl/create-relationship! client (faulting-grant (doc "p") :parent (doc "d")))
    (reset! observed [])
    (let [result (eacl/check-permission client request)]
      (is (= :no-permission (:permissionship result)))
      (is (= #{:allowed? :permissionship :cached? :cache-basis :evaluation} (set (keys result)))))
    (is (pos? (:qualifier-faults (meters))))
    (is (pos? (:masked-faults (meters))))
    ;; Once the target holds, the fault decides: nothing is masked.
    (eacl/create-relationship! client (grant (user "alice") :editor (doc "p")))
    (reset! observed [])
    (is (= failure (outcome #(eacl/check-permission client request))))
    (is (pos? (:qualifier-faults (meters))))
    (is (zero? (:masked-faults (meters))))
    ;; Without a request that reaches the faulting edge, both stay zero.
    (reset! observed [])
    (eacl/check-permission client (assoc request :subject (user "nobody")))
    (is (zero? (:qualifier-faults (meters))))
    (is (zero? (:masked-faults (meters))))))

(deftest an-absorbed-fault-leaves-the-same-residual-on-every-route
  ;; `view = reader + parent->editor`: reader is conditional on `role`; the
  ;; only arrow path crosses a faulting parent edge whose target holds no
  ;; editor, so it is false and the decision is the reader's residual. A
  ;; check, a detailed lookup item, a detailed count and a batch agree on it,
  ;; cache on and off.
  (let [client (client-with
                (str bad-caveat "
                 caveat staff(role string) { role == \"a\" }
                 definition user {}
                 definition doc {
                   relation reader: user with staff
                   relation parent: doc | doc with bad
                   relation editor: user
                   permission view = reader + parent->editor
                 }")
                ["alice" "d" "p"]
                [(assoc (grant (user "alice") :reader (doc "d")) :caveat "staff")
                 (faulting-grant (doc "p") :parent (doc "d"))])]
    (doseq [cache? [true false true]]
      (let [request {:subject (user "alice") :permission :view :caveat-context faulting :cache? cache?}
            check (eacl/check-permission client (assoc request :resource (doc "d")))
            item (first (:data (eacl/lookup-resources client (assoc request :resource/type :doc :first 10
                                                                    :result-policy :detailed))))
            counted (eacl/count-resources client (assoc request :resource/type :doc :result-policy :detailed))
            batch (first (eacl/check-permissions client {:caveat-context faulting :cache? cache?
                                                         :checks [{:subject (user "alice") :permission :view
                                                                   :resource (doc "d")}]}))]
        (is (= :conditional-permission (:permissionship check)))
        (is (= ["role"] (:missing-fields check)))
        (is (= (select-keys check [:permissionship :missing-fields :residual])
               (select-keys item [:permissionship :missing-fields :residual])
               (select-keys batch [:permissionship :missing-fields :residual])))
        (is (= "d" (get-in item [:object :id])))
        (is (= {:count 1 :definite-count 0 :conditional-count 1}
               (select-keys counted [:count :definite-count :conditional-count])))))))
