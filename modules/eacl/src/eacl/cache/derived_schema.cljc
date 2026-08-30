(ns eacl.cache.derived-schema
  "Count-bounded retention for cross-request schema derivations.

  One store contains individual immutable artifacts from every certified schema
  generation.  Source, lifecycle, adapter, schema, artifact, and semantic
  identity live in ordinary opaque cache keys; there is no generation registry
  or nested cache state. Artifacts are validated once before publication and
  then stored directly. Request work and validation stay outside the standard
  LRU atom transformations."
  (:require [clojure.set :as set]
            [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as standard-lru]
            [eacl.exact-integer :as exact-integer]))

(def default-max-entries 2048)

(def ^:private identity-fields
  #{:abi :source :adapter :schema-generation})

(def ^:private source-fields
  #{:backend :source-id :branch :source-lifecycle})

(def ^:private adapter-fields
  #{:backend :fingerprint :identity-contract :operator-capability})

(defrecord DerivedSchemaStore [storage])
(defrecord DerivedPartition [store identity artifact])

(defn store?
  [value]
  (instance? DerivedSchemaStore value))

(defn partition?
  [value]
  (instance? DerivedPartition value))

(defn store
  "Creates an empty derived-artifact LRU.

  The no-argument capacity is private runtime policy, not a semantic bound.
  Callers that expose a capacity pass one validated positive safe integer."
  ([] (store default-max-entries))
  ([max-entries]
   (->DerivedSchemaStore (standard-lru/store max-entries))))

(defn- invalid-identity!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/invalid-cache-key
            :eacl/error :eacl/invalid-cache-key
            :storage-domain :derived-schema}
           data))))

(defn- require-closed-map!
  [label expected value]
  (let [actual (when (map? value) (set (keys value)))]
    (when-not (= expected actual)
      (invalid-identity!
       (str "Derived-schema " label " identity is incomplete.")
       {:identity-part label
        :expected-fields expected
        :actual-fields actual
        :missing-fields (set/difference expected (or actual #{}))
        :unknown-fields (set/difference (or actual #{}) expected)}))))

(defn- validate-identity!
  [identity]
  (require-closed-map! :root identity-fields identity)
  (require-closed-map! :source source-fields (:source identity))
  (require-closed-map! :adapter adapter-fields (:adapter identity))
  (when-not (map? (:abi identity))
    (invalid-identity! "Derived-schema ABI identity must be a map."
                       {:identity-part :abi
                        :value (:abi identity)}))
  (when-not (keyword? (get-in identity [:source :backend]))
    (invalid-identity! "Derived-schema source backend must be a keyword."
                       {:identity-part :source
                        :field :backend
                        :value (get-in identity [:source :backend])}))
  (when-not (keyword? (get-in identity [:adapter :backend]))
    (invalid-identity! "Derived-schema adapter backend must be a keyword."
                       {:identity-part :adapter
                        :field :backend
                        :value (get-in identity [:adapter :backend])}))
  (when-not (keyword? (get-in identity [:adapter :identity-contract]))
    (invalid-identity!
     "Derived-schema adapter identity contract must be a keyword."
     {:identity-part :adapter
      :field :identity-contract
      :value (get-in identity [:adapter :identity-contract])}))
  (doseq [[part field value]
          [[:source :source-id (get-in identity [:source :source-id])]
           [:source :source-lifecycle
            (get-in identity [:source :source-lifecycle])]
           [:adapter :backend (get-in identity [:adapter :backend])]
           [:adapter :fingerprint (get-in identity [:adapter :fingerprint])]
           [:adapter :identity-contract
            (get-in identity [:adapter :identity-contract])]
           [:adapter :operator-capability
            (get-in identity [:adapter :operator-capability])]]]
    (when (nil? value)
      (invalid-identity! "Derived-schema identity field must be present."
                         {:identity-part part
                          :field field})))
  (let [generation (:schema-generation identity)]
    (when-not (and #?(:clj (integer? generation)
                      :cljs (and (number? generation)
                                 (js/Number.isSafeInteger generation)))
                   (not (neg? generation))
                   (<= generation exact-integer/maximum))
      (invalid-identity!
       "Derived-schema generation must be a non-negative safe integer."
       {:identity-part :root
        :field :schema-generation
        :value generation
        :maximum exact-integer/maximum})))
  (when-not (= (get-in identity [:source :backend])
               (get-in identity [:adapter :backend]))
    (invalid-identity! "Derived-schema source and adapter backends differ."
                       {:source-backend
                        (get-in identity [:source :backend])
                        :adapter-backend
                        (get-in identity [:adapter :backend])}))
  identity)

(defn artifact-partition
  "Creates a stateless artifact partition over one complete schema identity."
  [derived-store identity artifact]
  (when-not (store? derived-store)
    (invalid-identity! "Derived-schema partition requires a local LRU store."
                       {:value derived-store}))
  (validate-identity! identity)
  (when-not (keyword? artifact)
    (invalid-identity! "Derived-schema artifact must be a keyword."
                       {:artifact artifact}))
  (->DerivedPartition derived-store identity artifact))

(defn entry-key
  "Returns the full opaque key for one derived artifact."
  [derived-partition semantic]
  (when-not (partition? derived-partition)
    (invalid-identity! "Value is not a derived-schema partition."
                       {:value derived-partition}))
  (when (nil? semantic)
    (invalid-identity! "Derived-schema semantic identity must be complete."
                       {:artifact (:artifact derived-partition)}))
  (cache-key/domain-key
   :derived-schema
   [(:identity derived-partition)
    (:artifact derived-partition)
    semantic]))

(defn- validates?
  [valid? value]
  (try
    (boolean (valid? value))
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn lookup!
  "Looks up and touches one validated immutable artifact by its complete key.

  Supported publication is the sole ingress for this non-exported store, so
  resident hits need no envelope allocation or repeated artifact validation.
  Direct application mutation of private storage is outside the contract."
  [derived-partition semantic]
  (try
    (standard-lru/lookup!
     (:storage (:store derived-partition))
     (entry-key derived-partition semantic))
    (catch #?(:clj Throwable :cljs :default) _
      {:found? false :value nil})))

(defn publish!
  "Offers one already completed, validated artifact for absent-key insertion.

  An explicit callable validator is mandatory at this sole live-ingress
  boundary. Validation happens exactly once before the pure standard-LRU
  publication transform. A same-key winner is retained while the caller keeps
  its own completed value."
  [derived-partition semantic completed-value valid?]
  (when-not (ifn? valid?)
    (invalid-identity! "Derived-schema validator must be callable."
                       {:artifact (:artifact derived-partition)}))
  (if-not (validates? valid? completed-value)
    false
    (try
      (standard-lru/put-if-absent!
       (:storage (:store derived-partition))
       (entry-key derived-partition semantic)
       completed-value)
      (catch #?(:clj Throwable :cljs :default) _ false))))

(defn evict!
  [derived-partition semantic]
  (try
    (standard-lru/evict!
     (:storage (:store derived-partition))
     (entry-key derived-partition semantic))
    (catch #?(:clj Throwable :cljs :default) _ false)))

(defn clear!
  "Clears a store in place for compatibility-only refresh operations.

  Lifecycle expiry installs a fresh store instead, so late publishers remain
  attached to the old lifecycle."
  [derived-store]
  (when-not (store? derived-store)
    (invalid-identity! "Value is not a derived-schema store."
                       {:value derived-store}))
  (standard-lru/clear! (:storage derived-store)))

(defn stats
  "Returns count-capacity state without exposing library recency internals."
  [derived-store]
  (when-not (store? derived-store)
    (invalid-identity! "Value is not a derived-schema store."
                       {:value derived-store}))
  {:entry-count (standard-lru/entry-count (:storage derived-store))
   :max-entries (:max-entries (:storage derived-store))})
