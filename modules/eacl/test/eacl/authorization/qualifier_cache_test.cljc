(ns eacl.authorization.qualifier-cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.authorization.qualifier-cache :as cache]
            [eacl.cache.standard-lru :as lru]
            [eacl.caveats.definition :as definition]
            [eacl.relationships.qualifier :as qualifier]))

(def basis {:backend :test :source-id "s" :source-lifecycle "l" :branch nil
            :basis-kind :ordinary :revision 1 :exact-locator 1 :backend-snapshot-id "one"})

(defn request [store options]
  (fixtures/request (merge {:cache store :basis basis} options)))

(deftest exact-and-content-reuse-retain-data-and-reevaluate-each-request
  (let [store (cache/cache nil) reads (atom {}) calls (atom 0) decodes (atom 0)
        decode qualifier/decode]
    (with-redefs [qualifier/decode (fn [& args] (swap! decodes inc) (apply decode args))]
      (doseq [[time context expected] [[99 {"flag" true} :has-permission]
                                       [99 {} :conditional-permission]
                                       [99 {"flag" false} :no-permission]
                                       [100 {"flag" true} :no-permission]]]
        (is (= expected (evidence/permissionship
                         (qualification/qualify (request store {:time time :context context :reads reads :calls calls}) 1 [10 3])))))
      (is (= 1 @decodes))
      (is (= 1 (get @reads 3)))
      (is (= 3 @calls))
      (let [next-basis (assoc basis :revision 2 :exact-locator 2 :backend-snapshot-id "two")]
        (is (evidence/has? (qualification/qualify (request store {:basis next-basis :context {"flag" true} :reads reads}) 1 [10 3])))
        (is (= 1 @decodes) "complete content equality permits decode reuse on a later basis")
        (is (= 2 (get @reads 3)) "unknown-writer reuse still reads current qualifier content"))
      (doseq [changed [(assoc basis :source-lifecycle "reset")
                       (assoc basis :source-id "other")
                       (assoc basis :branch "other")
                       (assoc basis :speculative-id "prospective")]]
        (qualification/qualify (request store {:basis changed}) 1 [10 3]))
      (is (= 5 @decodes))
      (qualification/qualify (request store {:basis (assoc basis :revision 3) :version (constantly 8)}) 1 [10 3])
      (is (= 6 @decodes) "assertion version remains a decode identity dimension")
      (qualification/qualify (request store {:basis (assoc basis :revision 4)
                                             :db (assoc-in fixtures/fixture [1 :eacl/relation-version] 8)}) 1 [10 3])
      (is (= 7 @decodes) "owning Relation content remains part of the reuse proof"))))

(deftest complete-content-proofs-detect-unstamped-mutations-and-deletion
  (doseq [db [(assoc-in fixtures/fixture [3 qualifier/expiration-attribute] 98)
              (assoc-in fixtures/fixture [3 qualifier/marker-attribute] 99)
              (assoc-in fixtures/fixture [3 :unknown/field] true)
              (dissoc fixtures/fixture 3)
              (-> fixtures/fixture (assoc-in [3 qualifier/marker-attribute] 99) (assoc-in [1 :eacl.relation/caveats] #{}))
              (assoc fixtures/fixture 2 (assoc (definition/entity "enabled" fixtures/parameters "!flag") :db/id 2))
              (assoc-in fixtures/fixture [1 :eacl.relation/caveats] #{})]]
    (let [store (cache/cache nil)
          warm (request store {:context {"flag" true}})
          options {:db db :basis (assoc basis :revision 2) :context {"flag" true}}
          _ (is (evidence/has? (qualification/qualify warm 1 [10 3])))
          cached (qualification/qualify (request store options) 1 [10 3])
          uncached (qualification/qualify (request nil options) 1 [10 3])]
      (is (= cached uncached))
      (is (not (evidence/has? cached)))))
  (let [store (cache/cache nil)
        _ (qualification/qualify (request store {}) 1 [10 3])
        replacement (assoc-in fixtures/fixture [3 qualifier/expiration-attribute] 98)]
    (is (false? (qualification/qualify (request store {:db replacement :basis (assoc basis :source-lifecycle "reset")}) 1 [10 3])))))

(deftest decode-cache-publication-and-capacity-remain-optional-and-bounded
  (let [store (cache/cache {:max-entries 3})]
    (qualification/qualify (request store {:populate-cache? false}) 1 [10 3])
    (is (zero? (lru/entry-count (:entries store))))
    (doseq [revision (range 20)]
      (qualification/qualify (request store {:basis (assoc basis :revision revision)}) 1 [10 3])
      (is (<= (lru/entry-count (:entries store)) 3)))
    (let [before (lru/entry-count (:entries store))]
      (qualification/qualify (request store {:basis (assoc basis :revision 50) :populate-cache? false}) 1 [10 3])
      (is (= before (lru/entry-count (:entries store))))))
  (is (nil? (cache/cache false)))
  (doseq [option [true {} {:max-entries 0} {:max-entries 100001} {:max-entries 2 :writer :trusted}]]
    (is (= :eacl/invalid-config
           (try (cache/cache option) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error (:type (ex-data error))))))))
