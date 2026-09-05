(ns eacl.security.retention-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.cache :as cache]
            [eacl.cache.standard-lru :as lru]
            [eacl.continuation :as continuation]
            [eacl.client.range-reuse :as range-reuse]
            [eacl.security.retention :as retention]
            [eacl.security.protocols :as protocols]
            [eacl.security.format-test :refer [controller payload]]
            [eacl.security.keyring-test :refer [outcome]]))

(deftest cursor-retirement-cleans-only-the-owning-controller
  (let [ring (controller) foreign (controller)
        store (cursor/codec-cache {:max-entries 64})
        opts {:keyring-controller ring :cursor-codec-cache store}
        old (cursor/cursor->token payload opts)
        foreign-token (cursor/cursor->token payload (assoc opts :keyring-controller foreign))]
    (eacl/activate-security-key! ring :new)
    (let [new (cursor/cursor->token payload opts)]
      (is (= 3 (lru/entry-count (:token-store store))))
      (eacl/retire-security-key! ring :old)
      (is (= 3 (lru/entry-count (:token-store store))) "update does not scan client stores")
      (with-redefs [retention/on-retirement! (fn [& _] nil)]
        (is (= :security-key-unavailable (:reason (outcome #(cursor/token->cursor old opts)))))
        (is (= 3 (lru/entry-count (:token-store store)))))
      (is (= payload (cursor/token->cursor new opts)))
      (is (= 2 (lru/entry-count (:token-store store))))
      (is (= 2 (lru/entry-count (:reverse-token-store store))))
      (is (= 1 (lru/entry-count (:key-context-store store))) "unreachable generations removed only for this controller")
      (is (= payload (cursor/token->cursor foreign-token (assoc opts :keyring-controller foreign)))))))

(deftest cleanup-is-once-per-retirement-and-never-removes-racing-replacements
  (let [ring (controller) observed (atom nil) calls (atom 0)
        cleanup (fn [_] (swap! calls inc))]
    (retention/on-retirement! observed (protocols/-snapshot ring) cleanup)
    (eacl/activate-security-key! ring :new)
    (retention/on-retirement! observed (protocols/-snapshot ring) cleanup)
    (is (zero? @calls))
    (eacl/retire-security-key! ring :old)
    (dotimes [_ 3] (retention/on-retirement! observed (protocols/-snapshot ring) cleanup))
    (is (= 1 @calls)))
  (let [store (lru/store 4) old {:security-kid :old} local {:local true}
        entries lru/entries]
    (lru/put-if-absent! store :key old)
    (with-redefs [lru/entries (fn [s] (let [prior (entries s)] (lru/replace-if! s :key old local) prior))]
      (retention/prune! store (fn [_ v] (= :old (:security-kid v)))))
    (is (identical? local (:value (lru/lookup! store :key))))))

(deftest related-cursor-stores-clean-by-series-key
  (let [continuations (continuation/make-store {:max-entries 8})
        ranges (range-reuse/tier {:max-entries 8})
        basis (cache/basis-cache {:max-entries 8})
        rendered (:rendered-pages (cache/capture-cache-lifecycle basis))]
    (doseq [kid [:old :new]]
      (lru/put-if-absent! (:storage continuations) kid {:security-kid kid})
      (lru/put-if-absent! (:store ranges) [:walk [:scope kid] :first {}] {:segments []})
      (lru/put-if-absent! rendered kid {:security-kid kid}))
    (continuation/prune-retired! continuations #{:old})
    (range-reuse/prune-retired! ranges #{:old})
    (cache/prune-retired-rendered! basis #{:old})
    (doseq [store [(:storage continuations) (:store ranges) rendered]]
      (is (= 1 (lru/entry-count store))))
    (is (:found? (lru/lookup! rendered :new)))
    (is (:found? (lru/lookup! (:storage continuations) :new)))
    (is (:found? (lru/lookup! (:store ranges) [:walk [:scope :new] :first {}])))))
