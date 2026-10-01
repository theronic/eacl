(ns eacl.datascript.self-test
  "SpiceDB v1.56.0's `use self` through every public route. The expected
   answers for `schema` are SpiceDB's own (CheckPermission, LookupResources,
   LookupSubjects and ExpandPermissionTree against `serve-testing` on this
   schema and these relationships), with one deliberate difference: SpiceDB's
   LookupSubjects returns the resource's id under any requested subject type,
   which its CheckPermission denies; EACL returns the self subject only when
   the subject type is the resource type, as CheckPermission does. The
   qualified tests at the end follow EACL's strong-Kleene fault semantics."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(def schema
  "use self

   definition user {
     permission myself = self
   }

   definition folder {
     relation viewer: user
     permission myself = self
     permission view = viewer + self
   }

   definition doc {
     relation viewer: user | doc
     relation parent: folder
     permission view = viewer + self
     permission strict = viewer & self
     permission notself = viewer - self
     permission inherited = parent->myself
     permission folderview = parent->view
     permission only = self
   }")

(def object-ids ["a" "b" "1" "2" "3" "9" "f" "g" "zz"])

(defn- o [type id] (eacl/spice-object type id))

(def relationships
  [[(o :user "a") :viewer (o :doc "1")]
   [(o :doc "2") :viewer (o :doc "2")]
   [(o :doc "1") :viewer (o :doc "3")]
   [(o :folder "f") :parent (o :doc "1")]
   [(o :user "a") :viewer (o :folder "f")]])

(defn- client []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) object-ids))
    (eacl/create-relationships!
     client (mapv #(apply eacl/->Relationship %) relationships))
    {:conn conn :client client}))

(def checks
  ;; [resource permission subject expected] as SpiceDB answers them.
  [[(o :doc "1") :view (o :doc "1") true]
   [(o :doc "1") :view (o :user "a") true]
   [(o :doc "1") :view (o :doc "2") false]
   [(o :doc "1") :view (o :user "1") false]
   [(o :doc "9") :view (o :doc "9") true]
   [(o :doc "9") :only (o :doc "9") true]
   [(o :doc "2") :strict (o :doc "2") true]
   [(o :doc "1") :strict (o :doc "1") false]
   [(o :doc "3") :strict (o :doc "1") false]
   [(o :doc "1") :notself (o :user "a") true]
   [(o :doc "2") :notself (o :doc "2") false]
   [(o :doc "3") :notself (o :doc "1") true]
   [(o :doc "1") :inherited (o :folder "f") true]
   [(o :doc "1") :inherited (o :user "a") false]
   [(o :doc "1") :folderview (o :folder "f") true]
   [(o :doc "1") :folderview (o :user "a") true]
   [(o :user "a") :myself (o :user "a") true]
   [(o :user "a") :myself (o :user "b") false]
   [(o :user "a") :myself (o :doc "a") false]])

(defn- ids [page] (set (map :id (:data page))))

(deftest self-checks-match-spicedb-test
  (let [{:keys [client]} (client)]
    (doseq [[resource permission subject expected] checks
            cache? [true false]]
      (is (= expected
             (:allowed? (eacl/check-permission
                         client {:subject subject :permission permission
                                 :resource resource :cache? cache?})))
          [resource permission subject cache?]))
    (testing "one batch answers every check in order"
      (is (= (mapv #(nth % 3) checks)
             (mapv :allowed?
                   (eacl/check-permissions
                    client {:checks (mapv (fn [[resource permission subject]]
                                            {:subject subject :permission permission
                                             :resource resource})
                                          checks)})))))
    (testing "self is definite"
      (is (= :has-permission
             (:permissionship (eacl/check-permission
                               client {:subject (o :doc "1") :permission :view
                                       :resource (o :doc "1")})))))))

(deftest self-lookups-match-spicedb-test
  (let [{:keys [client]} (client)
        resources (fn [type permission subject]
                    (ids (eacl/lookup-resources
                          client {:subject subject :permission permission
                                  :resource/type type :first 10})))
        subjects (fn [resource permission type]
                   (ids (eacl/lookup-subjects
                         client {:resource resource :permission permission
                                 :subject/type type :first 10})))]
    (is (= #{"1" "3"} (resources :doc :view (o :doc "1"))))
    (is (= #{"9"} (resources :doc :view (o :doc "9"))))
    (is (= #{"9"} (resources :doc :only (o :doc "9"))))
    (is (= #{"2"} (resources :doc :strict (o :doc "2"))))
    (is (= #{} (resources :doc :notself (o :doc "2"))))
    (is (= #{"1"} (resources :doc :inherited (o :folder "f"))))
    (is (= #{"1"} (resources :doc :folderview (o :folder "f"))))
    (is (= #{"g"} (resources :folder :view (o :folder "g"))))
    (is (= #{"1"} (resources :doc :view (o :user "a"))))
    (is (= #{"zz"} (resources :user :myself (o :user "zz"))))
    (is (= #{"1"} (subjects (o :doc "1") :view :doc)))
    (is (= #{"1" "3"} (subjects (o :doc "3") :view :doc)))
    (is (= #{"9"} (subjects (o :doc "9") :view :doc)))
    (is (= #{"f"} (subjects (o :doc "1") :inherited :folder)))
    (is (= #{} (subjects (o :doc "2") :notself :doc)))
    (is (= #{"1"} (subjects (o :doc "1") :only :doc)))
    (testing "the self subject has the resource's type (SpiceDB also lists user:1 here)"
      (is (= #{"a"} (subjects (o :doc "1") :view :user)))
      (is (= #{} (subjects (o :doc "1") :only :user))))
    (testing "counts"
      (is (= 2 (:count (eacl/count-resources
                        client {:subject (o :doc "1") :permission :view
                                :resource/type :doc}))))
      (is (= 2 (:count (eacl/count-subjects
                        client {:resource (o :doc "3") :permission :view
                                :subject/type :doc})))))))

(defn- self-leaves [tree]
  (filter #(= :self (:expanded-relation %))
          (tree-seq #(seq (get-in % [:intermediate :children]))
                    #(get-in % [:intermediate :children])
                    tree)))

(deftest self-expand-read-and-schema-test
  (let [{:keys [client]} (client)]
    (testing "expand shows a self leaf whose subject is the resource"
      (doseq [permission [:view :strict :only]]
        (let [tree (:tree-root (eacl/expand-permission-tree
                                client {:resource (o :doc "1") :permission permission}))]
          (is (= [[(o :doc "1")]] (mapv #(get-in % [:leaf :subjects]) (self-leaves tree)))
              permission))))
    (testing "read-relationships authorizes through self"
      (is (= [[(o :doc "2") :viewer (o :doc "2")]]
             (mapv (juxt :subject :relation :resource)
                   (:data (eacl/read-relationships
                           client {:resource/type :doc
                                   :resource/relation :viewer
                                   :subject/type :doc
                                   :authorization {:subject (o :doc "2")
                                                   :permission :only
                                                   :on :resource}
                                   :first 10}))))))
    (testing "the identity relation is EACL's own"
      (is (not-any? #(= :_self (:eacl.relation/relation-name %))
                    (:relations (eacl/read-schema client))))
      (is (= :eacl/unknown-relation-or-permission
             (try (eacl/create-relationship!
                   client (eacl/->Relationship (o :doc "1") :_self (o :doc "1")))
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                    (:type (ex-data e)))))))
    (testing "a schema without self removes the identity relation"
      (eacl/write-schema!
       client
       "definition user {}
        definition folder {
          relation viewer: user
        }
        definition doc {
          relation viewer: user | doc
          relation parent: folder
          permission view = viewer
        }")
      (is (false? (:allowed? (eacl/check-permission
                              client {:subject (o :doc "9") :permission :view
                                      :resource (o :doc "9")})))))))

(deftest self-as-a-name-without-the-flag-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (testing "without `use self`, self is an ordinary relation name"
      (eacl/write-schema!
       client
       "definition user {}
        definition doc {
          relation self: user
          permission view = self
        }")
      (ds/transact! conn [{:eacl/id "a"} {:eacl/id "d"}])
      (eacl/create-relationship! client (eacl/->Relationship (o :user "a") :self (o :doc "d")))
      (is (true? (:allowed? (eacl/check-permission
                             client {:subject (o :user "a") :permission :view
                                     :resource (o :doc "d")})))))
    (testing "except as an arrow's base, which EACL's union plans cannot name"
      (is (= :eacl.schema/unsupported-feature
             (try (eacl/write-schema!
                   client
                   "definition user {}
                    definition group {
                      relation member: user
                      permission view = member
                    }
                    definition doc {
                      relation self: group
                      permission view = self->view
                    }")
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                    (:type (ex-data e)))))))))

;;; `self` is always definite: under strong-Kleene evaluation it absorbs a
;;; faulting operand exactly where a definite relation would, in either
;;; operand order and through every route, including the operator engine's
;;; static operand order, in which it ranks with the relation leaves.

(def ^:private faulting
  ;; `bad` reads a missing map key: only evaluation can detect it, so the
  ;; request passes context admission and the viewer branch faults.
  {"m" {}})

(defn- self-kleene-schema [flip?]
  (let [pair (fn [a op b] (str/join op (if flip? [b a] [a b])))]
    (str "use self
          caveat bad(m map<bool>) { m[\"x\"] }
          definition doc {
            relation viewer: doc | doc with bad
            relation banned: doc
            permission view = " (pair "viewer" " + " "self") "
            permission both = " (pair "viewer" " & " "self") "
            permission notself = viewer - self
            permission gated = (" (pair "viewer" " + " "self") ") - banned
          }")))

(defn- qualified-client [schema objects relationships]
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock (constantly 1000)})]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) objects))
    (eacl/create-relationships! client relationships)
    client))

(defn- outcome [f]
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

(defn- rows-outcome [f]
  (try [:rows (count (:data (f)))]
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
         [:error (:type (ex-data error))])))

(def ^:private failure [:error :eacl.authorization/evaluation-failure])

(defn- every-route
  "One permission of `subject` on doc:d through every authorization
  operation, with and without the answer cache."
  [client permission subject]
  (vec
   (for [cache? [true false true]]
     (let [request {:subject subject :permission permission
                    :caveat-context faulting :cache? cache?}]
       {:check (outcome #(eacl/check-permission client (assoc request :resource (o :doc "d"))))
        :can? (eacl/can? client (assoc request :resource (o :doc "d")))
        :batch (outcome #(eacl/check-permissions
                          client {:caveat-context faulting :cache? cache?
                                  :checks [(-> request (dissoc :caveat-context :cache?)
                                               (assoc :resource (o :doc "d")))]}))
        :lookup (outcome #(eacl/lookup-resources client (assoc request :resource/type :doc :first 10)))
        :count (outcome #(eacl/count-resources client (assoc request :resource/type :doc)))
        :subjects (outcome #(eacl/lookup-subjects
                             client {:resource (o :doc "d") :permission permission :subject/type :doc
                                     :caveat-context faulting :cache? cache? :first 10}))
        :authorized-read
        (rows-outcome #(eacl/read-relationships
                        client {:resource/type :doc :resource/id "d" :subject/type :doc
                                :caveat-context faulting :cache? cache?
                                :authorization {:subject subject :permission permission :on :resource}
                                :first 10}))}))))

(defn- decided [check rows]
  (let [has? (= :has-permission check)]
    {:check [:check check] :can? has? :batch [:batch [check]]
     :lookup [:page (if has? ["d"] [])] :count [:count (if has? 1 0)]
     :subjects [:page (if has? ["d"] [])] :authorized-read [:rows (if has? rows 0)]}))

(def ^:private faulted
  {:check failure :can? false :batch failure :lookup failure :count failure
   :subjects failure :authorized-read failure})

(deftest self-absorbs-a-faulting-operand-on-every-route-in-either-order-test
  (doseq [flip? [false true]
          objects [["d" "e"] ["e" "d"]]]
    (testing (pr-str [flip? objects])
      (testing "doc:d is its own self subject: self is true"
        (let [client (qualified-client
                      (self-kleene-schema flip?) objects
                      [(assoc (eacl/->Relationship (o :doc "d") :viewer (o :doc "d")) :caveat "bad")])]
          (doseq [[permission expected] [[:view (decided :has-permission 1)]
                                         [:gated (decided :has-permission 1)]
                                         [:notself (decided :no-permission 1)]
                                         [:both faulted]]
                  route (every-route client permission (o :doc "d"))]
            (is (= expected route) permission))))
      (testing "doc:e is not doc:d: self is false"
        (let [client (qualified-client
                      (self-kleene-schema flip?) objects
                      [(assoc (eacl/->Relationship (o :doc "e") :viewer (o :doc "d")) :caveat "bad")])]
          (doseq [cache? [true false true]]
            (let [request {:subject (o :doc "e") :resource (o :doc "d")
                           :caveat-context faulting :cache? cache?}
                  check #(outcome (fn [] (eacl/check-permission client (assoc request :permission %))))]
              (is (= [:check :no-permission] (check :both)))
              (is (= failure (check :view)))
              (is (= failure (check :notself)))
              (is (= failure (check :gated)))
              (is (= [:page []]
                     (outcome #(eacl/lookup-subjects
                                client {:resource (o :doc "d") :permission :both :subject/type :doc
                                        :caveat-context faulting :cache? cache? :first 10}))))
              (is (= [:rows 0]
                     (rows-outcome #(eacl/read-relationships
                                     client {:resource/type :doc :resource/id "d" :subject/type :doc
                                             :caveat-context faulting :cache? cache?
                                             :authorization {:subject (o :doc "e") :permission :both
                                                             :on :resource}
                                             :first 10})))))))))))

(deftest self-items-of-detailed-lookups-are-definite-test
  ;; A union-only lookup's conditional item is re-decided by the point check;
  ;; the self item is never conditional, and the other item keeps the
  ;; viewer's residual, so detailed items equal checks on both engines.
  (let [client (qualified-client
                "use self
                 caveat flagged(flag bool) { flag }
                 definition doc {
                   relation viewer: doc | doc with flagged
                   relation banned: doc
                   permission view = viewer + self
                   permission gated = (viewer + self) - banned
                 }"
                ["d" "x"]
                [(assoc (eacl/->Relationship (o :doc "d") :viewer (o :doc "d")) :caveat "flagged")
                 (assoc (eacl/->Relationship (o :doc "d") :viewer (o :doc "x")) :caveat "flagged")])]
    (doseq [permission [:view :gated]
            cache? [true false true]
            [context expected] [[{} {"d" :has-permission "x" :conditional-permission}]
                                [{"flag" true} {"d" :has-permission "x" :has-permission}]
                                [{"flag" false} {"d" :has-permission}]]]
      (testing (pr-str [permission cache? context])
        (let [request {:subject (o :doc "d") :permission permission
                       :caveat-context context :cache? cache?}
              items (:data (eacl/lookup-resources
                            client (assoc request :resource/type :doc :first 10
                                          :result-policy :detailed)))
              counted (eacl/count-resources
                       client (assoc request :resource/type :doc :result-policy :detailed))]
          (is (= expected
                 (into {} (map (juxt (comp :id :object) :permissionship)) items)))
          (is (= expected
                 (into {}
                       (keep (fn [id]
                               (let [p (:permissionship
                                        (eacl/check-permission
                                         client (assoc request :resource (o :doc id))))]
                                 (when-not (= :no-permission p) [id p]))))
                       ["d" "x"])))
          (is (= [(count expected)
                  (count (filter #{:has-permission} (vals expected)))
                  (count (filter #{:conditional-permission} (vals expected)))]
                 ((juxt :count :definite-count :conditional-count) counted)))
          (is (= (sort (keep (fn [[id p]] (when (= :has-permission p) id)) expected))
                 (sort (map :id (:data (eacl/lookup-resources
                                        client (assoc request :resource/type :doc :first 10))))))))))))
