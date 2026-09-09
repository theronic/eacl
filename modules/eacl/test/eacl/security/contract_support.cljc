(ns eacl.security.contract-support
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.core :as eacl]
            [eacl.contract-support :as contract]
            [eacl.causal-token :as causal]
            [eacl.cursor :as cursor]
            [eacl.spicedb.consistency :as consistency]
            [eacl.security.keyring-test :refer [material outcome]]))

(defn ring []
  (eacl/security-keyring {:keys {:old (material 1) :new (material 2)} :active-kid :old}))

(defn token [client]
  (let [snapshot (eacl/snapshot client)]
    (try (eacl/basis-token snapshot) (finally (eacl/release! snapshot)))))

(defn assert-live-rotation! [make-client seed-objects!]
  (let [primary (ring) dedicated (ring)
        config {:security-keyring-controller primary :zed-token-keyring-controller dedicated}
        a (make-client config) b (make-client config)
        query {:subject (contract/->user "user-1") :permission :view :resource/type :server :first 1}
        check {:subject (contract/->user "user-1") :permission :view :resource (contract/->server "server-1")}]
    (eacl/write-schema! a contract/smoke-schema)
    (seed-objects!)
    (let [write-token (:zed/token (eacl/create-relationships! a contract/smoke-relationships))
          old-zed (token a)
          first-page (eacl/lookup-resources a query)
          old-cursor (get-in first-page [:page-info :end-cursor])
          resume (assoc query :after old-cursor)
          expected (:data (eacl/lookup-resources b resume))]
      (is (string? old-cursor))
      (is (seq expected))
      (is (some? (causal/token-data {:keyring-controller dedicated} write-token)))
      (is (true? (:allowed? (eacl/check-permission b (assoc check :consistency (consistency/at-least-as-fresh old-zed))))))
      ;; Populate all local resume/rendered/answer paths before retirement.
      (is (= expected (:data (eacl/lookup-resources a resume))))
      (is (= expected (:data (eacl/lookup-resources a resume))))
      (eacl/activate-security-key! primary :new)
      (is (= expected (:data (eacl/lookup-resources b resume))))
      (let [new-page (eacl/lookup-resources a query)
            new-cursor (get-in new-page [:page-info :end-cursor])]
        (is (= :new (:security-kid (cursor/token->authenticated-cursor new-cursor {:keyring-controller primary}))))
        (eacl/retire-security-key! primary :old)
        (doseq [client [a b]]
          (is (= {:type :eacl.pagination/invalid-cursor :reason :security-key-unavailable}
                 (outcome #(eacl/lookup-resources client resume))))
          (is (= expected (:data (eacl/lookup-resources client (assoc query :after new-cursor)))))
          ;; Dedicated tokens are unaffected by primary retirement.
          (is (true? (:allowed? (eacl/check-permission client (assoc check :consistency (consistency/at-least-as-fresh old-zed))))))))
      (eacl/activate-security-key! dedicated :new)
      (let [new-zed (token b)]
        (eacl/retire-security-key! dedicated :old)
        (doseq [client [a b]]
          (is (= {:type :eacl/invalid-zed-token :reason :security-key-unavailable}
                 (outcome #(eacl/check-permission client (assoc check :consistency (consistency/at-least-as-fresh old-zed))))))
          (is (true? (:allowed? (eacl/check-permission client (assoc check :consistency (consistency/at-least-as-fresh new-zed)))))))))))

(defn assert-authenticated-cache! [make-client export! restore!]
  (let [controller (ring)
        config {:security-keyring-controller controller}
        a (make-client config) b (make-client config)
        bounds {:max-entries 64}
        check {:subject (contract/->user "user-1") :permission :view :resource (contract/->server "server-1")}
        local (assoc check :resource (contract/->server "server-2"))]
    (is (true? (:allowed? (eacl/check-permission a check))))
    (let [token (export! a bounds)]
      (is (string? token))
      (is (pos? (:entry-count (restore! b token bounds))))
      (let [hit (eacl/check-permission b check)]
        (is (true? (:allowed? hit)))
        (is (true? (:cached? hit)))
        (is (not (contains? hit :eacl.cache/imported?))))
      (is (true? (:allowed? (eacl/check-permission b local))))
      (is (true? (:cached? (eacl/check-permission b local))))
      (eacl/activate-security-key! controller :new)
      (is (true? (:cached? (eacl/check-permission b check))))
      (eacl/retire-security-key! controller :old)
      (let [recomputed (eacl/check-permission b check)
            uncached (eacl/check-permission b (assoc check :cache? false))]
        (is (false? (:cached? recomputed)))
        (is (= (contract/without-cache-provenance uncached)
               (contract/without-cache-provenance recomputed))))
      (is (true? (:cached? (eacl/check-permission b local))))
      (is (= {:restored? false :cache-miss? true :reason :security-key-unavailable}
             (restore! b token bounds)))
      (is (true? (:cached? (eacl/check-permission b local)))))))

(defn assert-client-security! [make-client seed-objects! export! restore!]
  (assert-live-rotation! make-client seed-objects!)
  (assert-authenticated-cache! make-client export! restore!))
