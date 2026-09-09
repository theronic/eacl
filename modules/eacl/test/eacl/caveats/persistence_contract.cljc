(ns eacl.caveats.persistence-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.values :as values]))

(def base-schema "definition user {}\ndefinition doc {\n relation viewer: user\n permission view = viewer\n}")
(def first-schema (str "caveat enabled(flag bool) { flag }\n" base-schema))
(def updated-schema (str "caveat enabled(flag bool) { !flag }\n" base-schema))
(def wrong-type-schema (str "caveat enabled(flag string) { flag == \"yes\" }\n" base-schema))

(defn error-type [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:type (ex-data e)))))

(defn check-persistence!
  [{:keys [write! snapshot read-schema entid generation transact! tempid history-stable? interleave! speculative]}]
  (let [read-current #(read-schema (snapshot))
        current-caveat #(first (:caveats (read-current)))
        current-id #(entid (snapshot) [:eacl.caveat/name "enabled"])]
    (write! first-schema)
    (let [before (snapshot)
          initial (current-caveat)
          eid (current-id)
          old-generation (generation before)]
      (is (= "enabled" (:eacl.caveat/name initial)))
      (is (= [:param "flag"] (:plan (definition/decode-entity initial))))
      (is (= :eacl.schema/unsupported-feature
             (error-type #(write! (str "caveat enabled(flag bool) { flag }\ndefinition user {}\n"
                                      "definition doc {\n relation viewer: user with enabled\n}")))))
      (is (= old-generation (generation (snapshot))) "disabled serving branches cannot change durable schema")
      (when speculative
        (let [{:keys [db components]} (speculative before updated-schema)]
          (is (= #{[:caveat "enabled"]} components))
          (is (= [:not [:param "flag"]]
                 (:plan (definition/decode-entity (first (:caveats (read-schema db)))))))
          (is (= initial (current-caveat)) "prospective replacement leaves the live source unchanged")))
      (write! updated-schema)
      (is (= eid (current-id)) "updates retain named identity")
      (is (not= old-generation (generation (snapshot))) "Caveat update advances schema generation")
      (is (= [:not [:param "flag"]] (:plan (definition/decode-entity (current-caveat)))))
      (when history-stable?
        (is (= initial (first (:caveats (read-schema before)))) "old snapshot retains source"))
      (let [generation-before (generation (snapshot))]
        (write! updated-schema)
        (is (= generation-before (generation (snapshot))) "identical schema is a no-op"))
      (let [qualifier {:db/id tempid
                       :eacl.relationship-qualifier/format-version 1
                       :eacl.relationship-qualifier/caveat eid
                       :eacl.relationship-qualifier/caveat-context
                       (values/encode-context [["flag" :bool]] {"flag" true})}
            report (transact! [qualifier])
            qid (get (:tempids report) tempid)
            generation-before (generation (snapshot))]
        (is (some? qid))
        (is (= :eacl.schema/caveat-in-use (error-type #(write! base-schema))))
        (is (= :eacl.caveat/invalid (error-type #(write! wrong-type-schema))))
        (is (= generation-before (generation (snapshot))) "failed changes are atomic")
        (is (= eid (current-id)))
        (transact! [[:db/retractEntity qid]]))
      (write! base-schema)
      (is (empty? (:caveats (read-current))))
      (is (nil? (current-id)))
      (is (= 1 (count (:relations (read-current)))) "ordinary schema survives Caveat removal")
      (when interleave!
        (write! first-schema)
        (is (= :eacl.schema/concurrent-write
               (error-type #(interleave! (fn [] (write! updated-schema))
                                         (fn [] (write! wrong-type-schema))))))
        (is (= [:not [:param "flag"]] (:plan (definition/decode-entity (current-caveat)))))))))
