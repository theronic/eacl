(ns eacl.cache.key
  "Canonical opaque keys for every shared EACL cache domain.

  Storage never interprets these values.  Domain constructors are deliberately
  pure and contain no hashing: full persistent-value equality is the collision
  check on both Clojure and ClojureScript."
  (:require [clojure.set :as set]))

(def key-format
  "Private cache-key ABI.  Bump whenever the logical key shape changes."
  :eacl.cache/key-v2)

(def ^:private authorization-fields
  #{:tier :source-lifecycle :abi :semantic :reuse})

(def ^:private authorization-domains
  #{:authorization-answer :authorization-subproblem})

(defn- invalid-key!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/invalid-cache-key
            :eacl/error :eacl/invalid-cache-key}
           data))))

(defn domain-key
  "Builds an opaque key from a keyword domain and its complete identity.

  The identity may be any immutable portable Clojure value.  Nil is rejected
  because it almost always means a caller omitted an authorization dimension."
  [storage-domain complete-domain-identity]
  (when-not (keyword? storage-domain)
    (invalid-key! "Cache storage domain must be a keyword."
                  {:storage-domain storage-domain}))
  (when (nil? complete-domain-identity)
    (invalid-key! "Cache domain identity must be complete."
                  {:storage-domain storage-domain}))
  [key-format storage-domain complete-domain-identity])

(defn- authorization-key
  [storage-domain reuse-mode identity]
  (when-not (contains? authorization-domains storage-domain)
    (invalid-key! "Unknown authorization cache domain."
                  {:storage-domain storage-domain}))
  (when-not (map? identity)
    (invalid-key! "Authorization cache identity must be a map."
                  {:storage-domain storage-domain :identity identity}))
  (let [actual-fields (set (keys identity))]
    (when-not (= authorization-fields actual-fields)
      (invalid-key!
       "Authorization cache identity has missing or unknown fields."
       {:storage-domain storage-domain
        :reuse-mode reuse-mode
        :missing-fields (set/difference authorization-fields actual-fields)
        :unknown-fields (set/difference actual-fields authorization-fields)})))
  (when-not (keyword? (:tier identity))
    (invalid-key! "Authorization cache tier must be a keyword."
                  {:storage-domain storage-domain :tier (:tier identity)}))
  (doseq [field [:source-lifecycle :abi :semantic :reuse]]
    (when (nil? (get identity field))
      (invalid-key! "Authorization cache identity field must be present."
                    {:storage-domain storage-domain
                     :reuse-mode reuse-mode
                     :field field})))
  (domain-key
   storage-domain
   [(:tier identity)
    reuse-mode
    (:source-lifecycle identity)
    (:abi identity)
    (:semantic identity)
    (:reuse identity)]))

(defn exact-answer-key
  "Builds a completed-answer key whose reuse identity is an exact basis."
  [identity]
  (authorization-key :authorization-answer :exact identity))

(defn managed-answer-key
  "Builds a completed-answer key whose reuse identity is a managed proof."
  [identity]
  (authorization-key :authorization-answer :managed identity))

(defn exact-denotation-key
  "Builds an exact denotation key for an exact basis."
  [identity]
  (authorization-key :authorization-subproblem :exact identity))
