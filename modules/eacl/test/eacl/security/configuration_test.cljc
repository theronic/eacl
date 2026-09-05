(ns eacl.security.configuration-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.core :as eacl]
            [eacl.security.configuration :as config]
            [eacl.security.keyring-test :refer [material outcome]]))

(defn controller [] (eacl/security-keyring {:keys {:old (material 1)} :active-kid :old}))

(deftest primary-and-dedicated-construction-matrix
  (let [primary (controller) dedicated (controller)
        primary-cases [{} {:security-key (material 1)}
                       {:security-key (material 1) :security-kid :named}
                       {:security-keyring {:named (material 1)} :security-kid :named}
                       {:security-keyring-controller primary}]
        zed-cases [{} {:zed-token-key (material 2)}
                   {:zed-token-key (material 2) :zed-token-kid :named}
                   {:zed-token-keyring {:named (material 2)} :zed-token-kid :named}
                   {:zed-token-keyring-controller dedicated}]]
    (doseq [p primary-cases z zed-cases]
      (let [scopes (config/format-scopes (merge p z))
            pc (get-in scopes [:format-options :keyring-controller])
            zc (get-in scopes [:zed-token-format-options :keyring-controller])]
        (is (eacl/security-keyring? pc))
        (is (eacl/security-keyring? zc))
        (is (= (empty? z) (identical? pc zc)))
        (when (:security-keyring-controller p) (is (identical? primary pc)))
        (when (:zed-token-keyring-controller z) (is (identical? dedicated zc)))))))

(deftest ambiguous-missing-and-invalid-key-sources-are-rejected
  (doseq [[controller-option key-option ring-option kid-option] [config/primary-keys config/zed-keys]
          bad [{controller-option nil}
               {controller-option (controller) key-option (material 1)}
               {controller-option (controller) ring-option {:old (material 1)}}
               {controller-option (controller) kid-option :old}
               {key-option (material 1) ring-option {:default (material 1)}}
               {key-option nil} {ring-option nil} {ring-option {}}
               {ring-option {:old (material 1)}}
               {key-option (material 1) kid-option nil}
               {key-option "short"} {ring-option {:default "short"}}]]
    (is (= :eacl/invalid-config (:type (outcome #(config/format-scopes bad))))))
  (is (= :missing-key-material
         (:reason (outcome #(config/format-scopes {:zed-token-kid :named}))))))

(deftest shared-controllers-and-static-scopes-have-explicit-ownership
  (let [shared (controller)
        one (config/format-scopes {:security-keyring-controller shared})
        two (config/format-scopes {:security-keyring-controller shared})
        static-a (config/format-scopes {:security-key (material 1)})
        static-b (config/format-scopes {:security-key (material 1)})]
    (eacl/add-security-key! shared :new (material 2))
    (eacl/activate-security-key! shared :new)
    (doseq [scopes [one two] scope [:format-options :zed-token-format-options]]
      (is (= :new (:active-kid (eacl/security-keyring-status (get-in scopes [scope :keyring-controller]))))))
    (is (not (identical? (get-in static-a [:format-options :keyring-controller])
                         (get-in static-b [:format-options :keyring-controller]))))))
