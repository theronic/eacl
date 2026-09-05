(ns eacl.datascript.evaluation-clock-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.request.context :as context]))

(deftest client-samples-once-and-snapshots-pin-time
  (let [conn (datascript/create-conn)
        now (atom 99) samples (atom []) times (atom [])
        client (datascript/make-client conn {:cache cache/no-cache
                                             :clock #(do (swap! samples conj @now) @now)})
        user (eacl/spice-object :user "user") doc (eacl/spice-object :doc "doc")
        check {:subject user :permission :view :resource doc}
        with-context context/call-with-context]
    (eacl/write-schema! client "definition user {}
                               definition doc {
                                 relation reader: user
                                 permission view = reader
                               }")
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "doc"}])
    (eacl/create-relationship! client (eacl/->Relationship user :reader doc))
    (reset! samples [])
    (with-redefs [context/call-with-context
                  (fn [ctx f]
                    (swap! times conj (:evaluation-time-ms (context/runtime ctx)))
                    (reset! now 100)
                    (with-context ctx f))]
      (is (:allowed? (eacl/check-permission client check)))
      (is (= [99] @samples))
      (is (= [99] @times))
      (reset! samples []) (reset! times [])
      (is (= 3 (count (eacl/check-permissions client {:checks [check check check]}))))
      (is (= [100] @samples))
      (is (= [100] @times))
      (reset! samples []) (reset! times []) (reset! now 90)
      (is (:allowed? (eacl/check-permission client check)))
      (is (= [90] @samples))
      (is (= [100] @times))
      (doseq [read [#(eacl/check-permission client (assoc check :evaluation-time-ms 0))
                   #(eacl/lookup-resources client {:subject user :resource/type :doc :permission :view :first 10})
                   #(eacl/lookup-subjects client {:resource doc :subject/type :user :permission :view :first 10})
                   #(eacl/count-resources client {:subject user :resource/type :doc :permission :view})
                   #(eacl/count-subjects client {:resource doc :subject/type :user :permission :view})
                   #(eacl/read-relationships client {:subject/type :user :first 10})]]
        (reset! now 105) (reset! samples []) (reset! times [])
        (read)
        (is (= [105] @samples))
        (is (= [105] @times)))
      (reset! now 110) (reset! samples []) (reset! times [])
      (let [snapshot (eacl/snapshot client)]
        (try
          (is (= [110] @samples))
          (reset! now 200) (reset! samples [])
          (is (:allowed? (eacl/check-permission snapshot check)))
          (is (:allowed? (eacl/check-permission snapshot check)))
          (is (= [] @samples))
          (is (= [110 110] @times))
          (let [prospective (eacl/with snapshot [])]
            (try
              (reset! times [])
              (is (:allowed? (eacl/check-permission prospective check)))
              (is (= [110] @times))
              (is (= [] @samples))
              (finally (eacl/release! prospective))))
          (finally (eacl/release! snapshot)))))))

(deftest evaluation-clock-is-a-trusted-client-dependency
  (let [conn (datascript/create-conn)]
    (doseq [clock [nil 0 {} "clock"]]
      (is (= :eacl/invalid-config
             (try (datascript/make-client conn {:clock clock}) nil
                  (catch #?(:clj Throwable :cljs :default) error (:type (ex-data error)))))))))
