(ns eacl.caveats.relation-removal-contract
  "Removing a Relation identity that still holds Relationships fails with
   `:eacl.schema/relation-in-use` and the full count, whatever qualifiers those
   Relationships carry. Speculative `:retain-inert` planning reports the
   retained Relationships whatever qualifier the first indexed row carries."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.core :as eacl]))

(def ^:private caveat-source "caveat enabled(flag bool) { flag }\n")

(def with-viewer
  (str caveat-source
       "definition user {}\ndefinition doc {\n"
       " relation viewer: user | user with enabled\n"
       " relation owner: user\n"
       " permission view = viewer + owner\n}"))

(def without-viewer
  (str caveat-source
       "definition user {}\ndefinition doc {\n"
       " relation owner: user\n"
       " permission view = owner\n}"))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(defn- relation-names [client]
  (set (map :eacl.relation/relation-name (:relations (eacl/read-schema client)))))

(defn- retained-orphan-diagnostics
  "Plans the removal speculatively. Explicit release keeps this portable to
   ClojureScript, which has no with-snapshot macro at this boundary."
  [client]
  (let [parent (eacl/snapshot client)]
    (try
      (let [child (eacl/with-schema parent without-viewer {:orphan-policy :retain-inert})]
        (try
          (eacl/speculative-diagnostics child)
          (finally (eacl/release! child))))
      (finally (eacl/release! parent)))))

(defn check!
  "`writer` returns the backend's qualifier writer; its native `:transact!`
   creates the application objects. Pass `:speculative? false` for a backend
   without speculative schema snapshots."
  [{:keys [client writer speculative?] :or {speculative? true}}]
  (eacl/write-schema! client {:schema with-viewer})
  (let [tx! (:transact! (:native (writer)))
        subject (eacl/spice-object :user "removal/u")
        documents (mapv #(eacl/spice-object :doc (str "removal/d" %)) (range 4))
        share #(eacl/->Relationship subject :viewer (nth documents %))
        ;; Endpoint indexes order one subject's rows by resource, so the first
        ;; row every scan meets is the qualified one created first.
        shares [(assoc (share 0) :valid-until-ms 253402300799999)
                (assoc (share 1) :valid-until-ms 1)
                (assoc (share 2) :caveat "enabled" :caveat-context {"flag" true})
                (share 3)]]
    (tx! (mapv #(hash-map :eacl/id (:id %)) (into [subject] documents)))
    (doseq [[retained relationship]
            (map vector (range 1 (inc (count shares))) shares)]
      (eacl/write-relationships! client [{:operation :touch :relationship relationship}])
      (testing (str retained " retained Relationships, the first one qualified")
        (let [data (error-data #(eacl/write-schema! client {:schema without-viewer}))]
          (is (= :eacl.schema/relation-in-use (:type data)))
          (is (= retained (:count data)))
          (is (= [:doc :viewer :user]
                 ((juxt :eacl.relation/resource-type
                        :eacl.relation/relation-name
                        :eacl.relation/subject-type)
                  (:relation data)))))
        (is (= #{:viewer :owner} (relation-names client))
            "a rejected removal leaves the stored schema unchanged")
        (when speculative?
          (is (= [{:type :eacl.speculative/retained-orphan-relationships
                   :relation [:relation :doc :viewer :user]
                   :present? true}]
                 (retained-orphan-diagnostics client))))))
    (eacl/write-relationships!
     client (mapv #(hash-map :operation :delete :relationship (share %))
                  (range (count shares))))
    (eacl/write-schema! client {:schema without-viewer})
    (is (= #{:owner} (relation-names client))
        "an unused Relation identity is removed")))
