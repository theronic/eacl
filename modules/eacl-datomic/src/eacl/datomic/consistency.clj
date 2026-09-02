(ns eacl.datomic.consistency
  "Purpose-specific signing-key derivation for Datomic Zed tokens.

  Live tokens are issued and authenticated by the shared eacl.causal-token
  codec. The superseded token constructors and the unreferenced observed-
  revision checkpoint store have no production consumers and are deliberately
  absent from this namespace."
  (:import [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private signing-algorithm "HmacSHA256")
(def ^:private signing-key-domain "eacl/zed-token/signing-key/v2")

(defn- utf8-bytes
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn- hmac-sha-256
  [^bytes key ^bytes value]
  (let [mac (Mac/getInstance signing-algorithm)]
    (.init mac (SecretKeySpec. key signing-algorithm))
    (.doFinal mac value)))

(defn derive-signing-key
  "Derives a purpose-specific Zed-token HMAC key from normalized root key bytes."
  [root-key]
  (when-not (and (bytes? root-key)
                 (pos? (alength ^bytes root-key)))
    (throw (ex-info "Zed token root key must be non-empty bytes."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :key :zed-token-key})))
  (hmac-sha-256 root-key (utf8-bytes signing-key-domain)))
