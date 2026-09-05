(ns eacl.security.format-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.core :as eacl]
            [eacl.causal-token :as causal]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]
            [eacl.security.protocols :as protocols]
            [eacl.security.keyring-test :refer [material outcome]]))

(defn controller []
  (eacl/security-keyring {:keys {:old (material 1) :new (material 2)} :active-kid :old}))
(defn options [c]
  {:keyring-controller c :domain "eacl/security-test/v1" :prefix "eacl_ring_" :payload-keys #{:value}})
(def payload {:value 17})
(defn envelope-kid [token]
  (:kid (secure/decode-canonical (secure/bytes->utf8 (secure/b64url-decode (subs token (count "eacl_ring_")))))))
(defn observed [c counter after-capture]
  (reify protocols/KeyringSource
    (-snapshot [_]
      (let [snapshot (protocols/-snapshot c)]
        (swap! counter inc)
        (when after-capture (after-capture))
        snapshot))
    (-derive-key [_ snapshot kid root-key domain version]
      (protocols/-derive-key c snapshot kid root-key domain version))))

(deftest authenticated-formats-capture-once-and-retain-overlap
  (let [c (controller) calls (atom 0)
        opts (options (observed c calls nil))
        old-token (secure/encode-authenticated opts payload)]
    (is (= 1 @calls))
    (is (= :old (envelope-kid old-token)))
    (reset! calls 0)
    (is (= payload (secure/decode-authenticated opts old-token)))
    (is (= 1 @calls))
    (eacl/activate-security-key! c :new)
    (let [new-token (secure/encode-authenticated opts payload)]
      (is (= :new (envelope-kid new-token)))
      (is (= payload (secure/decode-authenticated opts old-token)))
      (is (= payload (secure/decode-authenticated opts new-token)))
      (eacl/retire-security-key! c :old)
      (is (= :security-key-unavailable (:reason (outcome #(secure/decode-authenticated opts old-token)))))
      (is (= payload (secure/decode-authenticated opts new-token)))
      (is (= :authentication-failed
             (:reason (outcome #(secure/decode-authenticated (assoc opts :domain "another/domain") new-token))))))))

(deftest activation-and-retirement-linearize-after-a-captured-state
  (let [c (controller) calls (atom 0)
        old-token (secure/encode-authenticated
                   (options (observed c calls #(eacl/activate-security-key! c :new))) payload)]
    (is (= 1 @calls))
    (is (= :old (envelope-kid old-token)))
    (is (= :new (:active-kid (eacl/security-keyring-status c))))
    (reset! calls 0)
    (is (= payload
           (secure/decode-authenticated
            (options (observed c calls #(eacl/retire-security-key! c :old))) old-token)))
    (is (= 1 @calls))
    (is (= :security-key-unavailable
           (:reason (outcome #(secure/decode-authenticated (options c) old-token)))))))

(deftest derived-keys-are-isolated-by-generation-id-domain-and-format
  (let [c (controller) count (atom 0) original secure/derive-key]
    (with-redefs [secure/derive-key (fn [key domain] (swap! count inc) (original key domain))]
      (let [opts (secure/capture-keyring (options c))
            root (get (:keyring opts) :old)
            first-key (secure/domain-key opts :old root "one" 1)]
        (is (= first-key (secure/domain-key opts :old root "one" 1)))
        (is (= 1 @count))
        (is (not= first-key (secure/domain-key opts :old root "two" 1)))
        (secure/domain-key opts :old root "one" 2)
        (secure/domain-key opts :new (get (:keyring opts) :new) "one" 1)
        (is (= 4 @count))
        (eacl/activate-security-key! c :new)
        (let [next (secure/capture-keyring (options c))]
          (is (not= (:generation (:keyring-snapshot opts)) (:generation (:keyring-snapshot next))))
          (secure/domain-key next :old root "one" 1)
          (is (= 5 @count)))))))

(deftest causal-tokens-use-the-supplied-controller
  (let [c (controller)
        opts {:keyring-controller c :now-seconds 50}
        native {:backend :test :source-id "source" :source-lifecycle "life" :branch nil
                :revision 1 :exact-locator nil :issued-at 10 :expires-at 100}
        token (causal/issue opts native)]
    (is (= 1 (:revision (causal/token-data opts token))))
    (eacl/activate-security-key! c :new)
    (is (= 1 (:revision (causal/token-data opts token))))
    (eacl/retire-security-key! c :old)
    (is (= {:type :eacl/invalid-zed-token :reason :security-key-unavailable}
           (outcome #(causal/token-data opts token))))))

(deftest cursor-cache-hits-still-observe-live-key-acceptance
  (let [c (controller) calls (atom 0)
        opts {:keyring-controller (observed c calls nil)
              :cursor-codec-cache (cursor/codec-cache) :now-seconds 50}
        value {:edge 17}
        token (cursor/cursor->token value opts)]
    (is (= 1 @calls))
    (reset! calls 0)
    (let [work (atom {})]
      (binding [cursor/*codec-work* work]
        (is (= :old (:security-kid (cursor/token->authenticated-cursor token opts)))))
      (is (= 1 @calls))
      (is (empty? @work)))
    (eacl/activate-security-key! c :new)
    (reset! calls 0)
    (is (= value (cursor/token->cursor token opts)))
    (is (= 1 @calls))
    (let [new-token (cursor/cursor->token value opts)]
      (is (not= token new-token))
      (is (= :new (:security-kid (cursor/token->authenticated-cursor new-token opts))))
      (eacl/retire-security-key! c :old)
      ;; Physical codec entries deliberately remain: eligibility is independent.
      (reset! calls 0)
      (is (= {:type :eacl.pagination/invalid-cursor :reason :security-key-unavailable}
             (outcome #(cursor/token->cursor token opts))))
      (is (= 1 @calls))
      (is (= value (cursor/token->cursor new-token opts))))))

(deftest cursor-races-use-one-captured-generation
  (let [c (controller) calls (atom 0)
        token (cursor/cursor->token
               payload {:keyring-controller (observed c calls #(eacl/activate-security-key! c :new))})]
    (is (= 1 @calls))
    (is (= :old (:security-kid (cursor/token->authenticated-cursor token {:keyring-controller c}))))
    (reset! calls 0)
    (is (= payload (cursor/token->cursor
                    token {:keyring-controller (observed c calls #(eacl/retire-security-key! c :old))})))
    (is (= 1 @calls))
    (is (= :security-key-unavailable
           (:reason (outcome #(cursor/token->cursor token {:keyring-controller c})))))))

(deftest cursor-age-and-retirement-are-distinct
  (let [c (controller) opts {:keyring-controller c :now-seconds 50 :cursor-ttl-seconds 10}
        token (cursor/cursor->token payload opts)]
    (eacl/activate-security-key! c :new)
    (is (= payload (cursor/token->cursor token (assoc opts :now-seconds 59))))
    (is (= {:type :eacl.pagination/expired-cursor :reason :expired}
           (outcome #(cursor/token->cursor token (assoc opts :now-seconds 60)))))
    (eacl/retire-security-key! c :old)
    (is (= {:type :eacl.pagination/invalid-cursor :reason :security-key-unavailable}
           (outcome #(cursor/token->cursor token (assoc opts :now-seconds 60)))))))
