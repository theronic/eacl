(ns eacl.core-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.core :as eacl]))

(defn- record-call!
  [calls operation request response]
  (swap! calls conj [operation request])
  response)

(deftest boolean-compatibility-requires-definite-grants-and-preserves-operational-errors
  (let [requests [#(eacl/can? nil {})
                  #(eacl/can? nil {} :view {})
                  #(eacl/can? nil {} :view {} :fully-consistent)]]
    (doseq [[decision expected]
            [[{:allowed? true} true]
             [{:allowed? true :permissionship :has-permission} true]
             [{:allowed? false :permissionship :no-permission} false]
             [{:allowed? false :permissionship :conditional-permission} false]
             [{:allowed? true :permissionship :conditional-permission} false]
             [{:allowed? :conditional} false]
             [{:allowed? nil} false]]
            request requests]
      (with-redefs [eacl/check-permission (fn [& _] decision)]
        (is (= expected (request)))))
    (doseq [request requests]
      (with-redefs [eacl/check-permission
                    (fn [& _] (throw (ex-info "Qualified failure"
                                              {:type :eacl.authorization/evaluation-failure})))]
        (is (false? (request)))))
    (doseq [type [:eacl.execution/cancelled :eacl.execution/deadline-exceeded
                  :eacl.execution/resource-limit-exceeded :eacl.caveat/invalid
                  :backend/failure]
            request requests]
      (let [error (ex-info "Must propagate" {:type type})]
        (with-redefs [eacl/check-permission (fn [& _] (throw error))]
          (is (identical? error
                          (try (request) nil
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) caught
                                 caught)))))))))

(defrecord RecordingSnapshot [calls released basis token]
  eacl/IAuthorizationReader
  (-check-permission [_ request]
    (record-call! calls :check-permission request
                  {:allowed? true
                   :cached? false
                   :cache-basis (:revision basis)
                   :evaluation :demand}))
  (-read-schema [_ request]
    (record-call! calls :read-schema request {:relations [] :permissions []}))
  (-read-relationships [_ request]
    (record-call! calls :read-relationships request {:data []}))
  (-lookup-resources [_ request]
    (record-call! calls :lookup-resources request {:data []}))
  (-lookup-subjects [_ request]
    (record-call! calls :lookup-subjects request {:data []}))
  (-count-resources [_ request]
    (record-call! calls :count-resources request {:count 0}))
  (-count-subjects [_ request]
    (record-call! calls :count-subjects request {:count 0}))
  (-expand-permission-tree [_ request]
    (record-call! calls :expand-permission-tree request {:tree-root nil}))

  eacl/IAuthorizationSnapshot
  (-basis [_] basis)
  (-basis-token [_] token)
  (-release! [_] (compare-and-set! released false true))
  (-released? [_] @released))

(defrecord RecordingAcl [calls snapshot]
  eacl/IAuthorizationReader
  (-check-permission [_ request]
    (record-call! calls :check-permission request
                  {:allowed? true
                   :cached? false
                   :cache-basis 1
                   :evaluation :demand}))
  (-read-schema [_ request]
    (record-call! calls :read-schema request {:relations [] :permissions []}))
  (-read-relationships [_ request]
    (record-call! calls :read-relationships request {:data []}))
  (-lookup-resources [_ request]
    (record-call! calls :lookup-resources request {:data []}))
  (-lookup-subjects [_ request]
    (record-call! calls :lookup-subjects request {:data []}))
  (-count-resources [_ request]
    (record-call! calls :count-resources request {:count 0}))
  (-count-subjects [_ request]
    (record-call! calls :count-subjects request {:count 0}))
  (-expand-permission-tree [_ request]
    (record-call! calls :expand-permission-tree request {:tree-root nil}))

  eacl/IAuthorizationWriter
  (-write-schema! [_ request]
    (record-call! calls :write-schema request {:zed/token "schema"}))
  (-write-relationships! [_ request]
    (record-call! calls :write-relationships request {:zed/token "relationships"}))
  (-delete-object! [_ request]
    (record-call! calls :delete-object request {:retracted-datoms 0}))

  eacl/ISnapshotSource
  (-snapshot [_ consistency options]
    (record-call! calls :snapshot
                  {:consistency consistency :options options}
                  snapshot)))

(defrecord ReaderOnly [calls]
  eacl/IAuthorizationReader
  (-check-permission [_ request]
    (record-call! calls :check-permission request
                  {:allowed? false
                   :cached? false
                   :cache-basis nil
                   :evaluation :demand}))
  (-read-schema [_ request]
    (record-call! calls :read-schema request nil))
  (-read-relationships [_ request]
    (record-call! calls :read-relationships request nil))
  (-lookup-resources [_ request]
    (record-call! calls :lookup-resources request nil))
  (-lookup-subjects [_ request]
    (record-call! calls :lookup-subjects request nil))
  (-count-resources [_ request]
    (record-call! calls :count-resources request nil))
  (-count-subjects [_ request]
    (record-call! calls :count-subjects request nil))
  (-expand-permission-tree [_ request]
    (record-call! calls :expand-permission-tree request nil)))

(defrecord RemoteEndpoint [calls]
  ;; Deliberately implements only the wire-level reader and writer roles.  It
  ;; has no local database value, source, snapshot, runtime, or cache.
  eacl/IAuthorizationReader
  (-check-permission [_ request]
    (record-call! calls :check-permission request
                  {:allowed? true :remote? true}))
  (-read-schema [_ request]
    (record-call! calls :read-schema request {:remote? true}))
  (-read-relationships [_ request]
    (record-call! calls :read-relationships request {:remote? true}))
  (-lookup-resources [_ request]
    (record-call! calls :lookup-resources request {:remote? true}))
  (-lookup-subjects [_ request]
    (record-call! calls :lookup-subjects request {:remote? true}))
  (-count-resources [_ request]
    (record-call! calls :count-resources request {:remote? true}))
  (-count-subjects [_ request]
    (record-call! calls :count-subjects request {:remote? true}))
  (-expand-permission-tree [_ request]
    (record-call! calls :expand-permission-tree request {:remote? true}))

  eacl/IAuthorizationWriter
  (-write-schema! [_ request]
    (record-call! calls :write-schema request {:remote? true}))
  (-write-relationships! [_ request]
    (record-call! calls :write-relationships request {:remote? true}))
  (-delete-object! [_ request]
    (record-call! calls :delete-object request {:remote? true})))

(defrecord BatchedReader [response]
  eacl/IAuthorizationReader
  (-check-permission [_ _] nil)
  (-read-schema [_ _] nil)
  (-read-relationships [_ _] nil)
  (-lookup-resources [_ _] nil)
  (-lookup-subjects [_ _] nil)
  (-count-resources [_ _] nil)
  (-count-subjects [_ _] nil)
  (-expand-permission-tree [_ _] nil)

  eacl/IBatchedAuthorization
  (-check-permissions [_ request]
    [response request]))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest public-read-normalization-test
  (let [calls (atom [])
        snapshot (->RecordingSnapshot
                  calls (atom false) {:revision 1} "basis-token")
        acl (->RecordingAcl calls snapshot)
        subject {:type :user :id "user-1"}
        resource {:type :document :id "document-1"}
        demand {:subject subject :permission :view :resource resource}]
    (is (true? (eacl/can? acl subject :view resource)))
    (is (true? (eacl/can? acl subject :view resource :fully-consistent)))
    (is (true? (eacl/can? acl demand)))
    (is (true? (:allowed? (eacl/check-permission acl demand))))
    (is (true? (:allowed? (eacl/check-permission
                           acl subject :view resource))))
    (is (true? (:allowed? (eacl/check-permission
                           acl subject :view resource :fully-consistent))))
    (is (map? (eacl/read-schema acl)))
    (is (map? (eacl/read-schema acl {:consistency :fully-consistent})))
    (is (map? (eacl/read-relationships acl {:resource/type :document})))
    (is (map? (eacl/lookup-resources acl {:resource/type :document})))
    (is (map? (eacl/lookup-subjects acl {:subject/type :user})))
    (is (map? (eacl/count-resources acl {:resource/type :document})))
    (is (map? (eacl/count-subjects acl {:subject/type :user})))
    (is (map? (eacl/expand-permission-tree acl demand)))
    (is (= [:check-permission demand]
           (first @calls)))
    (is (= [:check-permission (assoc demand :consistency :fully-consistent)]
           (second @calls)))
    (is (= [:read-schema {}]
           (nth @calls 6)))))

(deftest public-write-normalization-test
  (let [calls (atom [])
        snapshot (->RecordingSnapshot
                  calls (atom false) {:revision 1} "basis-token")
        acl (->RecordingAcl calls snapshot)
        subject {:type :user :id "user-1"}
        resource {:type :document :id "document-1"}
        relationship (eacl/->Relationship subject :viewer resource)]
    (eacl/write-schema! acl "definition user {}")
    (eacl/write-relationships!
     acl [(eacl/->RelationshipUpdate :touch relationship)])
    (eacl/write-relationship! acl :touch subject :viewer resource)
    (eacl/write-relationship!
     acl {:operation :touch
          :subject subject
          :relation :viewer
          :resource resource})
    (eacl/create-relationships! acl [relationship])
    (eacl/create-relationship! acl relationship)
    (eacl/create-relationship! acl subject :viewer resource)
    (eacl/delete-relationships! acl [relationship])
    (eacl/delete-relationships! acl {:data [relationship]})
    (eacl/delete-relationship! acl relationship)
    (eacl/delete-relationship! acl subject :viewer resource)
    (eacl/delete-object! acl resource)
    (is (= [:write-schema {:schema "definition user {}"}]
           (first @calls)))
    (is (= 10
           (count (filter #(= :write-relationships (first %)) @calls))))
    (is (= [:delete-object {:object resource}]
           (last @calls)))))

(deftest snapshot-capability-and-lifecycle-test
  (let [calls (atom [])
        released (atom false)
        snapshot (->RecordingSnapshot
                  calls released {:revision 7 :basis-kind :ordinary}
                  "basis-token")
        acl (->RecordingAcl calls snapshot)]
    (is (eacl/acl? acl))
    (is (not (eacl/snapshot? acl)))
    (is (eacl/snapshot? snapshot))
    (is (identical? snapshot (eacl/snapshot acl)))
    (is (identical? snapshot (eacl/snapshot acl :fully-consistent)))
    (is (= {:revision 7 :basis-kind :ordinary} (eacl/basis snapshot)))
    (is (= "basis-token" (eacl/basis-token snapshot)))
    (is (false? (eacl/released? snapshot)))
    (is (true? (eacl/release! snapshot)))
    (is (true? (eacl/released? snapshot)))
    (is (false? (eacl/release! snapshot)))))

#?(:clj
   (deftest with-snapshot-releases-in-finally-test
     (let [calls (atom [])
           released (atom false)
           snapshot (->RecordingSnapshot calls released {:revision 1} "token")]
       (is (= :result
              (eacl/with-snapshot [selected snapshot]
                (is (identical? snapshot selected))
                :result)))
       (is @released))))

(deftest capability-errors-are-dual-classified-test
  (let [reader (->ReaderOnly (atom []))
        snapshot (->RecordingSnapshot
                  (atom []) (atom false) {:revision 1} "token")]
    (testing "non-reader values fail before protocol dispatch"
      (let [data (error-data #(eacl/can? {} {} :view {}))]
        (is (= :eacl/invalid-authorization-target (:type data)))
        (is (= (:type data) (:eacl/error data)))))
    (testing "read-only and snapshot targets reject writes"
      (doseq [target [reader snapshot]]
        (let [data (error-data #(eacl/write-schema! target "schema"))]
          (is (= :eacl/unsupported-capability (:type data)))
          (is (= (:type data) (:eacl/error data)))
          (is (= :write (:capability data))))))
    (testing "non-sources reject snapshot selection"
      (let [data (error-data #(eacl/snapshot reader))]
        (is (= :eacl/unsupported-capability (:type data)))
        (is (= :snapshot (:capability data)))))))

(deftest batched-permission-dispatch-test
  (let [request {:checks []}]
    (is (= [:ok request]
           (eacl/check-permissions
            (->BatchedReader :ok) request)))
    (let [data (error-data
                #(eacl/check-permissions
                  (->ReaderOnly (atom [])) request))]
      (is (= :eacl/unsupported-capability (:type data)))
      (is (= :check-permissions (:capability data))))))

(deftest remote-reader-writer-needs-no-local-extension-test
  (let [calls (atom [])
        remote (->RemoteEndpoint calls)
        subject (eacl/spice-object :user "remote-user")
        resource (eacl/spice-object :document "remote-document")
        demand {:subject subject :permission :view :resource resource}
        relationship (eacl/->Relationship subject :viewer resource)]
    (is (true? (eacl/can? remote demand)))
    (is (:remote? (eacl/check-permission remote demand)))
    (is (:remote? (eacl/read-schema remote)))
    (is (:remote? (eacl/read-relationships remote {})))
    (is (:remote? (eacl/lookup-resources remote {})))
    (is (:remote? (eacl/lookup-subjects remote {})))
    (is (:remote? (eacl/count-resources remote {})))
    (is (:remote? (eacl/count-subjects remote {})))
    (is (:remote? (eacl/expand-permission-tree remote demand)))
    (is (:remote? (eacl/write-schema! remote "definition user {}")))
    (is (:remote?
         (eacl/create-relationship! remote relationship)))
    (is (:remote? (eacl/delete-object! remote resource)))
    (is (false? (eacl/acl? remote)))
    (is (false? (eacl/snapshot? remote)))
    (is (false? (eacl/released? remote)))
    (doseq [[capability operation]
            [[:snapshot #(eacl/snapshot remote)]
             [:basis #(eacl/basis remote)]
             [:basis-token #(eacl/basis-token remote)]
             [:release #(eacl/release! remote)]
             [:check-permissions
              #(eacl/check-permissions remote {:checks []})]]]
      (let [data (error-data operation)]
        (is (= :eacl/unsupported-capability (:type data)))
        (is (= (:type data) (:eacl/error data)))
        (is (= capability (:capability data)))
        (is (= :writer (:target data)))))
    (is (= 12 (count @calls)))))
