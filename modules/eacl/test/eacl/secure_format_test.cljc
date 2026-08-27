(ns eacl.secure-format-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.causal-token :as token]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]))

(def old-key (vec (range 32)))
(def current-key (vec (range 32 64)))
(def options
  {:current-kid :current
   :keyring {:old old-key
             :current current-key}})

(def portable-cursor-vector
  "eacl_c5_OmN1cnJlbnQ.AAECAwQFBgcICQoL.QZ_P3mknLrCiPflRU2H_hccCTQDt3aST6krVrhIEy_I4dnWIfbanUmV8cfHj6sDjMhIN8Cd0lHIwkYS96UxCLc_WdfnW2VPzKBMSBiUyisCbhT9UFcFddkxT5ucySUPkLI31YQ68regKJar5aOo90d9lE3m6f7zG_vos_8kHIuEb.sOgle-nVuH1EijEmdIzlDQKGcUjJmxvyjlgYMDrjkUQ")

(def legacy-portable-cursor-vector
  "eacl_c4_OmN1cnJlbnQ.ezpjdXJzb3IgezpraW5kIDpyZWxhdGlvbnNoaXBzLCA6b2Zmc2V0IDIsIDpzY29wZSBbOnJlYWQgezpzdWJqZWN0L2lkICJ1MSJ9XSwgOnYgOX0sIDpleHBpcmVzLWF0IDEwNSwgOmlzc3VlZC1hdCAxMDAsIDp2ZXJzaW9uIDR9.977hLzhIglQl_tClD4faSO8IvVpkEFUatzI9hAFDHfY")

(def portable-cache-vector
  "eacl_ce3_ezpraWQgOmN1cnJlbnQsIDpwYXlsb2FkICJlenBqYjIxd2RYUmxaQzFoZENCN09tZHlZWEJvSURkOUxDQTZaR1Z3Wlc1a1pXNWplUzF6WTI5d1pTQjdPbkpsYkdGMGFXOXVjeUJiTVRGZExDQTZjMk5vWlcxaElGdGJPbVJ2WTNWdFpXNTBJRHAyYVdWM1hWMTlMQ0E2YTJWNUlIczZjMlZ0WVc1MGFXTXRhMlY1SUZzNlkyRnVQeUFpZFRFaVhYMHNJRHByYVc1a0lEcGliMjlzWldGdUxDQTZjRzl5ZEdGaWJHVXRkbVZ5YzJsdmJpQXhMQ0E2Y0hKdmIyWWdlenB5Wld4aGRHbHZibk1nZXpFeElDSnlNU0o5TENBNmMyTm9aVzFoSUNKek1TSjlMQ0E2ZG1Gc2FXUmhkR1ZrTFdGMElIczZaM0poY0dnZ04zMHNJRHAyWVd4MVpTQjBjblZsTENBNmRtVnljMmx2YmlBemZRIiwgOnRhZyAiNEk0ZmxKQUZGcjZVczFGQnZiVl9uWDZ6ekczaDVEWlM1d3ExVjBQRUxPWSIsIDp2IDF9")

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(defn- tamper
  [value]
  (let [replacement (if (= "A" (subs value 0 1)) "B" "A")]
    (str replacement (subs value 1))))

(defn- tamper-authenticator
  [prefix token]
  (let [envelope
        (-> token
            (subs (count prefix))
            secure/b64url-decode
            secure/bytes->utf8
            secure/decode-canonical)
        envelope' (update envelope :tag tamper)]
    (str prefix
         (secure/b64url-encode
          (secure/utf8-bytes
          (secure/encode-canonical envelope'))))))

(defn- tamper-compact-authenticator
  [token]
  (let [[kid nonce ciphertext tag]
        (str/split
         (subs token (count cursor/cursor-prefix))
         #"\."
         -1)]
    (str cursor/cursor-prefix
         kid "." nonce "." ciphertext "." (tamper tag))))

(defn- tamper-encrypted-cursor-payload
  [token]
  (let [[kid nonce ciphertext tag]
        (str/split
         (subs token (count cursor/cursor-prefix))
         #"\."
         -1)
        ciphertext-bytes (secure/b64url-decode ciphertext)
        tampered-ciphertext
        (secure/b64url-encode
         (update ciphertext-bytes 0 bit-xor 1))]
    (str cursor/cursor-prefix
         kid "." nonce "." tampered-ciphertext "." tag)))

(deftest canonical-portable-format-test
  (is (= (secure/encode-canonical {:b #{3 2 1} :a [1 true nil]})
         (secure/encode-canonical {:a [1 true nil] :b #{1 2 3}})))
  (is (= "{:scope [:read {:subject/id \"u1\"}]}"
         (secure/encode-canonical
          {:scope [:read {:subject/id "u1"}]}))
      "canonical maps never depend on host namespace-map abbreviations")
  (is (= {:a [1 true nil] :b #{1 2 3}}
         (secure/decode-canonical
          (secure/encode-canonical {:b #{3 2 1} :a [1 true nil]}))))
  (is (= {:minimum secure/minimum-safe-integer
          :maximum secure/maximum-safe-integer}
         (secure/decode-canonical
          (secure/encode-canonical
           {:maximum secure/maximum-safe-integer
            :minimum secure/minimum-safe-integer}))))
  (is (= "\"quote:\\\" slash:\\\\ controls:\\b\\t\\n\\f\\r\\u0001\""
         (secure/encode-canonical
          "quote:\" slash:\\ controls:\b\t\n\f\r\u0001")))
  (testing "hostile bounds fail before use"
    (is (= :integer-out-of-range
           (:reason
            (error-data
             #(secure/encode-canonical
               (inc secure/maximum-safe-integer))))))
    (is (= :integer-out-of-range
           (:reason
            (error-data
             #(secure/encode-canonical
               (dec secure/minimum-safe-integer))))))
    (is (= :too-deep
           (:reason
            (error-data
             #(secure/encode-canonical
               [[[[[:too-deep]]]]]
               {:maximum-depth 2})))))
    (is (= :too-large
           (:reason
            (error-data
             #(secure/decode-canonical
               (apply str (repeat 100 "x"))
               {:maximum-size 10})))))
    (is (= :unknown-fields
           (:reason
            (error-data
             #(secure/decode-canonical
               "{:allowed 1 :forged 2}"
               {:allowed-keys #{:allowed}})))))
    (is (= :malformed
           (:reason
            (error-data
             #(secure/decode-canonical
               "{:duplicate 1, :duplicate 2}"))))
        "duplicate map fields never receive last-write-wins semantics")
    (is (= :malformed
           (:reason
            (error-data
             #(secure/decode-canonical
               "{:allowed 1} {:forged 2}"))))
        "a second top-level form is never ignored")
    (is (= :malformed
           (:reason
            (error-data
             #(secure/decode-canonical
               "{:allowed 1} #_{:forged 2}"))))
        "reader-discard cannot hide trailing input")
    (is (= :malformed
           (:reason
            (error-data
             #(secure/decode-canonical
               "#eacl/unknown {:value true}"))))
        "unknown tagged values are outside the accepted wire language")
    (let [unicode "hé😀"
          bytes (secure/utf8-bytes unicode)]
      (is (every? #(<= 0 % 255) bytes)
          "UTF-8 has the same unsigned byte representation on every runtime")
      (is (= unicode (secure/bytes->utf8 bytes))))
    #?(:clj
       (let [ambiguous-keyword (keyword "a/b" "c")
             ordinary-keyword (keyword "a" "b/c")]
         (is (not= ambiguous-keyword ordinary-keyword))
         (is (= :ambiguous-keyword
                (:reason
                 (error-data
                  #(secure/encode-canonical
                    {ambiguous-keyword 1
                     ordinary-keyword 2}))))
             "distinct keyword keys must never collapse to one wire spelling")))
    #?(:clj
       (let [unpaired-surrogate (str (char 0xD800))]
         (is (= :invalid-unicode
                (:reason
                 (error-data
                  #(secure/utf8-bytes unpaired-surrogate)))))
         (is (= :invalid-unicode
                (:reason
                 (error-data
                  #(secure/encode-canonical unpaired-surrogate)))))
         (is (= :malformed-utf8
                (:reason
                 (error-data
                  #(secure/bytes->utf8 [0xC3 0x28])))))
         (is (= "😀"
                (secure/bytes->utf8
                 (byte-array (map unchecked-byte [0xF0 0x9F 0x98 0x80]))))
             "legacy JVM signed byte arrays remain accepted")
         (is (= "\"😀\""
                (secure/encode-canonical "😀"))
             "valid surrogate pairs remain portable")))))

(deftest domain-separation-and-key-rotation-test
  (let [payload {:v 1 :answer true}
        old-options (assoc options :current-kid :old)
        old-token
        (secure/encode-authenticated
         (merge old-options {:domain "test/domain/a" :prefix "test_a_"})
         payload)]
    (is (= payload
           (secure/decode-authenticated
            (merge options
                   {:domain "test/domain/a"
                    :prefix "test_a_"
                    :payload-keys #{:v :answer}})
            old-token)))
    (is (= :malformed-token
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/b"
                       :prefix "test_b_"
                       :payload-keys #{:v :answer}})
               old-token)))))
    (is (= :authentication-failed
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/b"
                       :prefix "test_a_"
                       :payload-keys #{:v :answer}})
               old-token))))
        "domain separation still applies when an attacker reuses the prefix")
    (is (= :authentication-failed
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/a"
                       :prefix "test_a_"
                       :keyring {:old current-key}})
               old-token))))
        "the key identifier cannot substitute a different key")
    (is (= :unknown-fields
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/a"
                       :prefix "test_a_"
                       :payload-keys #{:v}})
               old-token))))
        "an authenticated but context-invalid payload still fails closed")
    (is (= :authentication-failed
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/a"
                       :prefix "test_a_"
                       :payload-keys #{:v :answer}})
               (tamper-authenticator "test_a_" old-token))))))))

(deftest canonical-records-digest-test
  (let [records [[:alpha {:b 2 :a 1}]
                 [:beta #{3 1 2}]]
        digest (secure/canonical-records-digest
                "test/records/v1" records)]
    (is (= "sDmOlTDyZBZzLIccAloDY2q5FoRl_-dDELuWK27Q-6U"
           digest)
        "CLJ and CLJS must implement the same framing and digest contract")
    (is (= digest
           (secure/canonical-records-digest
            "test/records/v1"
            [[:alpha {:a 1 :b 2}]
             [:beta #{1 2 3}]])))
    (is (not=
         digest
         (secure/canonical-records-digest
          "test/records/v1" (reverse records))))
    (is (not=
         digest
         (secure/canonical-records-digest
          "test/records/v2" records)))
    (is (= 43 (count digest))))
  (testing "the complete sequence may exceed the whole-value format bound"
    (is (= 43
           (count
            (secure/canonical-records-digest
             "test/large-record-sequence/v1"
             (map (fn [n] [:record n]) (range 20000))))))))

(deftest constant-work-tag-comparison-test
  (is (secure/secure-equal? [0 1 2 3] [0 1 2 3]))
  (is (false? (secure/secure-equal? [9 1 2 3] [0 1 2 3])))
  (is (false? (secure/secure-equal? [0 1 2 9] [0 1 2 3])))
  (is (false? (secure/secure-equal? [0 1 2] [0 1 2 3]))))

(deftest causal-token-round-trip-and-rejection-test
  (let [payload {:backend :datascript
                 :source-id "family"
                 :source-lifecycle "secure-format-test"
                 :branch nil
                 :revision 7
                 :exact-locator nil}
        encoded (token/issue options payload)]
    (is (= (merge payload
                  {:version 4}
                  (select-keys (token/token-data options encoded)
                               [:issued-at :expires-at]))
           (token/token-data options
                             {:backend :datascript
                              :source-id "family"
                              :source-lifecycle "secure-format-test"
                              :branch nil}
                             encoded)))
    (is (= :scope-mismatch
           (:reason
            (error-data
             #(token/token-data
               options
               {:backend :datascript
                :source-id "other"
                :source-lifecycle "secure-format-test"
                :branch nil}
               encoded)))))
    (is (= :expired
           (:reason
            (error-data
             #(token/token-data
               (assoc options :now-seconds
                      (inc (:expires-at
                            (token/token-data options encoded))))
               encoded)))))
    (is (= :malformed-token
           (:reason
            (error-data #(token/token-data options "17")))))))

(deftest source-lifecycle-is-bounded-portable-canonical-data-test
  (is (= :invalid-source-lifecycle
         (:reason
          (error-data
           #(token/validate-source-lifecycle!
             {:invalid (fn [])})))))
  (is (= :invalid-source-lifecycle
         (:reason
          (error-data
           #(token/validate-source-lifecycle!
             {:oversized (apply str
                                (repeat
                                 token/maximum-scope-characters
                                 "x"))}))))))

(deftest authenticated-portable-cursor-test
  (let [value {:v 8 :edge {:kind :lookup-eid} :position [1 "a"]}
        encoded (cursor/cursor->token value options)]
    (is (= value (cursor/token->cursor encoded options)))
    (is (= :malformed-token
           (:reason
            (error-data
             #(cursor/token->cursor "eacl1_e30=" options)))))
    (is (= :authentication-failed
           (:reason
            (error-data
             #(cursor/token->cursor
               (tamper-compact-authenticator encoded)
               options)))))))

(deftest encrypted-cursor-rejects-tampering-before-payload-parse-test
  (let [value {:v 12
               :scope "private-scope"
               :edge {:kind :stable-edge :ordinal 7}
               :proof "private-proof"}
        encoded (cursor/cursor->token value options)
        tampered (tamper-encrypted-cursor-payload encoded)
        codec-work (atom {})
        data
        (binding [cursor/*codec-work* codec-work]
          (error-data #(cursor/token->cursor tampered options)))]
    (is (= :authentication-failed (:reason data)))
    (is (= 1 (:authentication-passes @codec-work))
        "the visible key id is accepted and the ciphertext is authenticated")
    (is (zero? (:decryption-passes @codec-work 0)))
    (is (zero? (:payload-canonical-passes @codec-work 0))
        "the private payload is neither decrypted nor parsed before authentication")))

(deftest encrypted-cursor-hides-payload-and-randomizes-ciphertext-test
  (let [value {:v 12
               :scope "scope-secret-7f8a"
               :position "position-secret-31c2"
               :proof "proof-secret-98bd"}
        token-a (cursor/cursor->token value (assoc options :now-seconds 100))
        token-b (cursor/cursor->token value (assoc options :now-seconds 100))
        plaintext
        (secure/encode-canonical
         {:version cursor/cursor-version
          :cursor value
          :issued-at 100
          :expires-at nil})
        encoded-plaintext
        (secure/b64url-encode (secure/utf8-bytes plaintext))]
    (is (not= token-a token-b)
        "fresh random nonces produce distinct ciphertexts")
    (is (not (str/includes? token-a encoded-plaintext))
        "the old base64-plaintext payload is absent")
    (doseq [secret ["scope-secret-7f8a"
                    "position-secret-31c2"
                    "proof-secret-98bd"]]
      (is (not (str/includes? token-a secret))))
    (is (= value (cursor/token->cursor token-a
                                      (assoc options :now-seconds 100))))))

(deftest encrypted-cursor-size-bound-rejects-instead-of-truncating-test
  (let [value {:v 12 :scope (apply str (repeat 80 "x"))}
        nonce (vec (range 12))
        full-token
        (with-redefs [secure/random-bytes (fn [_] nonce)]
          (cursor/cursor->token value options))
        maximum-size (dec (count full-token))
        data
        (with-redefs [secure/random-bytes (fn [_] nonce)]
          (error-data
           #(cursor/cursor->token
             value (assoc options :maximum-size maximum-size))))]
    (is (= :too-large (:reason data)))
    (is (= :malformed-token
           (:reason
            (error-data
             #(cursor/token->cursor
               full-token
               (assoc options :maximum-size maximum-size))))))))

(deftest encrypted-cursor-key-rotation-test
  (let [value {:v 12 :edge {:kind :least-path-edge :coords [1 2 3]}}
        old-token
        (cursor/cursor->token value (assoc options :current-kid :old))]
    (is (= value (cursor/token->cursor old-token options))
        "the retained old key decrypts a cursor after the current kid rotates")
    (is (= :authentication-failed
           (:reason
            (error-data
             #(cursor/token->cursor
               old-token
               {:current-kid :current
                :keyring {:current current-key}})))))))

(deftest private-cursor-codec-cache-test
  (let [value {:v 10
               :scope [:lookup {:subject/id "user-1"}]
               :edge {:kind :lookup-eid :result-eid "document-1"}}
        codec-cache (cursor/codec-cache {:max-entries 2})
        cached-options
        (assoc options
               :cursor-codec-cache codec-cache
               :completed-cache-request? false)
        encode-work (atom {})
        encoded
        (binding [cursor/*codec-work* encode-work]
          (let [first-token
                (cursor/cursor->token value cached-options)
                repeated-token
                (cursor/cursor->token value cached-options)]
            (is (= first-token repeated-token))
            (is (= 1 (:encode-calls @encode-work))
                "EACL memoizes its own cursor independently of answer caching")
            first-token))]
    (testing "a token minted by this cache skips repeated authenticated decode"
      (let [decode-work (atom {})]
        (binding [cursor/*codec-work* decode-work]
        (is (= value
                 (cursor/token->cursor encoded cached-options)))
          (is (empty? @decode-work)))))
    (testing "an unknown token still passes through authenticated decoding"
      (is (= :authentication-failed
             (:reason
              (error-data
               #(cursor/token->cursor
                 (tamper-compact-authenticator encoded)
                 cached-options))))))))

(deftest private-cursor-codec-cache-retains-exact-ttl-semantics-test
  (let [value {:v 12
               :scope [:lookup {:subject/id "ttl-user"}]
               :edge {:kind :stable-edge :position [1 "document-1"]}}
        codec-cache (cursor/codec-cache {:max-entries 2})
        cached-options
        (assoc options
               :cursor-codec-cache codec-cache
               :cursor-ttl-seconds 10)
        encode-work (atom {})
        token-1
        (binding [cursor/*codec-work* encode-work]
          (let [minted
                (cursor/cursor->token
                 value (assoc cached-options :now-seconds 100))
                reused
                (cursor/cursor->token
                 value (assoc cached-options :now-seconds 109))]
            (is (= minted reused)
                "an identical payload reuses its still-valid authenticated token")
            (is (= 1 (:encode-calls @encode-work)))
            minted))]
    (testing "a cached decode still enforces the protected expiry"
      (let [decode-work (atom {})
            authenticated
            (binding [cursor/*codec-work* decode-work]
              (cursor/token->authenticated-cursor
               token-1 (assoc cached-options :now-seconds 110)))]
        (is (true? (:authenticated? authenticated)))
        (is (true? (:expired? authenticated)))
        (is (= 110 (:expired-at authenticated)))
        (is (empty? @decode-work)
            "self-minted tokens need neither decryption nor re-authentication"))
      (is (= :expired
             (:reason
              (error-data
               #(cursor/token->cursor
                 token-1 (assoc cached-options :now-seconds 110)))))))
    (testing "expiry remints without corrupting the newer reverse mapping"
      (let [token-2
            (cursor/cursor->token
             value (assoc cached-options :now-seconds 110))
            other-token
            (cursor/cursor->token
             {:v 12 :scope :other}
             (assoc cached-options :now-seconds 110))]
        (is (not= token-1 token-2))
        ;; max-entries=2 evicts token-1 here. Its eviction must not delete the
        ;; token-2 reverse lookup for the same cursor.
        (is (string? other-token))
        (is (= token-2
               (cursor/cursor->token
                value (assoc cached-options :now-seconds 111))))))
    (testing "issuance policies never share an encode lookup"
      (is (not=
           (cursor/cursor->token
            value (assoc cached-options :now-seconds 111))
           (cursor/cursor->token
            value
            (assoc cached-options
                   :cursor-ttl-seconds 20
                   :now-seconds 111)))))))

(deftest private-cursor-construction-context-cache-is-bounded-test
  (let [codec-cache (cursor/codec-cache {:max-entries 2})
        builds (atom {})
        build
        (fn [key]
          #(do
             (swap! builds update key (fnil inc 0))
             {:built-from key}))]
    (is (= {:built-from :one}
           (cursor/memoized-context! codec-cache :one (build :one))))
    (is (= {:built-from :two}
           (cursor/memoized-context! codec-cache :two (build :two))))
    (let [token
          (cursor/cursor->token
           {:v 10 :scope :scope :edge {:kind :lookup-eid}}
           (assoc options :cursor-codec-cache codec-cache))]
      (is (string? token))
      (is (= {:built-from :one}
             (cursor/memoized-context! codec-cache :one (build :one)))
          "token publication preserves construction contexts"))
    (is (= {:built-from :three}
           (cursor/memoized-context! codec-cache :three (build :three))))
    (is (= {:built-from :two}
           (cursor/memoized-context! codec-cache :two (build :two))))
    (is (= {:built-from :one}
           (cursor/memoized-context! codec-cache :one (build :one)))
        "the oldest context is rebuilt after bounded eviction")
    (is (= {:one 2 :two 1 :three 1} @builds))))

(deftest encrypted-cursors-reuse-client-private-key-context-test
  (let [codec-cache (cursor/codec-cache {:max-entries 4})
        cached-options (assoc options :cursor-codec-cache codec-cache)
        work (atom {})]
    (binding [cursor/*codec-work* work]
      (is (string?
           (cursor/cursor->token
            {:v 10 :scope :one :edge {:kind :lookup-eid :value 1}}
            cached-options)))
      (is (string?
           (cursor/cursor->token
            {:v 10 :scope :two :edge {:kind :lookup-eid :value 2}}
            cached-options))))
    (is (= 2 (:encode-calls @work)))
    (is (= 1 (:key-context-builds @work))
        "the first cursor derives and encodes the configured key context")
    (is (= 1 (:key-context-cache-hits @work))
        "the next distinct cursor reuses key derivation without reusing a token")))

(deftest cursor-construction-cache-does-not-cache-tokens-test
  (let [construction-cache (cursor/codec-cache {:max-entries 4})
        construction-options
        (assoc options :cursor-construction-cache construction-cache)
        value {:v 10 :scope :same :edge {:kind :lookup-eid :value 1}}
        work (atom {})
        [token-a token-b]
        (binding [cursor/*codec-work* work]
          [(cursor/cursor->token value construction-options)
           (cursor/cursor->token value construction-options)])]
    (is (not= token-a token-b)
        "construction reuse retains a fresh nonce for every uncached token")
    (is (= value (cursor/token->cursor token-a construction-options)))
    (is (= value (cursor/token->cursor token-b construction-options)))
    (is (= 2 (:encode-calls @work)))
    (is (= 1 (:key-context-builds @work)))
    (is (= 1 (:key-context-cache-hits @work)))))

#?(:clj
   (deftest encrypted-cursor-key-context-is-concurrency-safe-test
     (let [codec-cache (cursor/codec-cache {:max-entries 128})
           cached-options (assoc options :cursor-codec-cache codec-cache)
           values (mapv (fn [n]
                          {:v 12
                           :scope [:concurrent n]
                           :edge {:kind :lookup-eid :value n}})
                        (range 64))
           tokens (mapv deref
                        (mapv #(future
                                 (cursor/cursor->token % cached-options))
                              values))]
       (is (= 64 (count (set tokens))))
       (is (= values
              (mapv #(cursor/token->cursor
                      % (dissoc cached-options :cursor-codec-cache))
                    tokens))))))

(deftest encrypted-cursor-operation-count-and-growth-test
  (let [small
        {:v 10
         :scope "scope"
         :edge {:kind :lookup-eid
                :path-frontiers {[:a] "1"}}}
        large
        (assoc-in
         small
         [:edge :path-frontiers]
         (into {}
               (map (fn [n] [[(keyword (str "path-" n))] (str n)]))
               (range 512)))
        encode-work (atom {})
        token
        (binding [cursor/*codec-work* encode-work]
          (cursor/cursor->token large options))
        decode-work (atom {})]
    (is (= large
           (binding [cursor/*codec-work* decode-work]
             (cursor/token->cursor token options))))
    (doseq [work [@encode-work @decode-work]]
      (is (= 1 (:payload-canonical-passes work)))
      (is (= 1 (:authentication-passes work))))
    (is (= 1 (:encryption-passes @encode-work)))
    (is (= 1 (:decryption-passes @decode-work)))
    (is (= 4 (:base64-encode-passes @encode-work)))
    (is (= 4 (:base64-decode-passes @decode-work)))
    (is (< (count token)
           (+ 128 (* 2 (:payload-input-bytes @encode-work))))
        "compact framing grows linearly and leaves no nested envelope pass")))

(deftest portable-cursor-expiry-boundary-test
  (let [value {:v 8 :edge {:kind :lookup-eid} :position [1 "a"]}
        issued-at 100
        encoded
        (cursor/cursor->token
         value
         (assoc options
                :cursor-ttl-seconds 1
                :now-seconds issued-at))]
    (is (= value
           (cursor/token->cursor
            encoded
            (assoc options :now-seconds issued-at))))
    (is (= :expired
           (:reason
            (error-data
             #(cursor/token->cursor
               encoded
               (assoc options :now-seconds (inc issued-at))))))
        "expires-at is the first invalid second, matching Datomic cursors")))

(deftest authenticated-cross-runtime-vectors-test
  (let [vector-options
        {:current-kid :current
         :keyring {:current current-key}
         :now-seconds 100
         :cursor-ttl-seconds 5}
        cursor-payload
        {:v 9
         :kind :relationships
         :scope [:read {:subject/id "u1"}]
         :offset 2}
        cache-payload
        {:version 3
         :portable-version 1
         :key {:semantic-key [:can? "u1"]}
         :kind :boolean
         :computed-at {:graph 7}
         :validated-at {:graph 7}
         :dependency-scope
         {:schema [[:document :view]]
          :relations [11]}
         :proof
         {:schema "s1"
          :relations {11 "r1"}}
         :value true}
        cache-options
        (merge
         vector-options
         {:domain "eacl/cache-entry/envelope/v3"
          :prefix "eacl_ce3_"})]
    (is (= portable-cursor-vector
           (with-redefs [secure/random-bytes
                         (fn [n]
                           (is (= 12 n))
                           (vec (range n)))]
             (cursor/cursor->token
              cursor-payload
              vector-options))))
    (is (= cursor-payload
           (cursor/token->cursor
            portable-cursor-vector
            vector-options)))
    (is (= :malformed-token
           (:reason
            (error-data
             #(cursor/token->cursor
               legacy-portable-cursor-vector
               vector-options))))
        "the removed authenticated-plaintext cursor format is not decoded")
    (is (= portable-cache-vector
           (secure/encode-authenticated
            cache-options
            cache-payload)))
    (is (= cache-payload
           (secure/decode-authenticated
            (assoc
             cache-options
             :payload-keys
             #{:version
               :portable-version
               :key
               :kind
               :computed-at
               :validated-at
               :dependency-scope
               :proof
               :value})
            portable-cache-vector)))))
