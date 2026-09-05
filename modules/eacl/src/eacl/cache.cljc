(ns eacl.cache
  "Client-private authorization caching over flat bounded stores.

  Exact and managed reuse are semantic key construction concerns. Storage is
  only a bounded partial map of opaque keys to immutable completed values;
  misses compute independently and publication never owns computation."
  (:require [eacl.authorization.result :as authorization-result]
            [eacl.authorization.temporal :as temporal]
            [eacl.backend.v8 :as backend]
            [eacl.cache-identity :as cache-identity]
            [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as lru]
            [eacl.causal-token :as causal-token]
            [eacl.engine.v8 :as engine]
            [eacl.exact-integer :as exact-integer]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.relationships.mutations :as relationship-mutations]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem])
  #?(:clj (:import [java.util.concurrent.atomic LongAdder])))

(defrecord NoCache [])

(def no-cache
  "Public sentinel that disables client-private authorization caching."
  (->NoCache))

(defn no-cache?
  [value]
  (instance? NoCache value))

(defn validate-request-cache-option!
  "Validates the per-request `:cache?` execution control."
  [cache-option]
  (when-not (or (nil? cache-option) (boolean? cache-option))
    (throw
     (ex-info
      "EACL Error: per-request :cache? must be true or false."
      {:type :eacl/invalid-request
       :eacl/error :eacl/invalid-request
       :key :cache?
       :value cache-option})))
  cache-option)

(defn validate-request-populate-option!
  "Validates the per-request `:populate-cache?` publication control."
  [populate-option]
  (when-not (or (nil? populate-option) (boolean? populate-option))
    (throw
     (ex-info
      "EACL Error: per-request :populate-cache? must be true or false."
      {:type :eacl/invalid-request
       :eacl/error :eacl/invalid-request
       :key :populate-cache?
       :value populate-option})))
  populate-option)

(defn lookup-page-query-identity
  "Builds the semantic identity for an authenticated lookup page.

  Signed transport cursor bytes are excluded. The authenticated internal
  ordinal/result boundary remains part of the identity."
  [public-query internal-query]
  {:public
   (-> (cache-identity/successful-result-query public-query)
       (dissoc :consistency :after :before))
   :internal
   (-> (cache-identity/successful-result-query internal-query)
       (dissoc :consistency))})

(defrecord CacheLifecycle
           [token subproblems rendered-pages content-revision])

(defrecord BasisCache
           [lifecycle metrics subproblem-options
            managed-lifting-disabled? reported-contract-violations
            proof-contract-reporter telemetry-enabled?
            exact-hits managed-hits rendered-page-hits content-change-fn])

(def basis-snapshot-format
  "Version identifier for flat process-neutral authorization-cache snapshots."
  :eacl.cache/basis-snapshot-v2)

(def ^:private answer-entry-format :eacl.cache/completed-answer-v2)
(def rendered-page-entry-format
  "Version identifier for exact public transport-page values."
  :eacl.cache/rendered-page-v5)

(defn- metadata-free-portable-data?
  [value allow-records? {:keys [maximum-depth maximum-entries
                                maximum-characters]}]
  (loop [pending [[value 0]]
         entries 0
         characters 0]
    (if (empty? pending)
      true
      (let [[item depth] (peek pending)
            remaining (pop pending)
            next-entries (inc entries)
            next-characters
            (+ characters
               (cond
                 (string? item) (count item)
                 (keyword? item) (count (str item))
                 :else 0))]
        (cond
          (or (and maximum-depth (> depth maximum-depth))
              (and maximum-entries (> next-entries maximum-entries))
              (and maximum-characters
                   (> next-characters maximum-characters)))
          false

          (some? (meta item))
          false

          (or (nil? item)
              (boolean? item)
              (string? item)
              (keyword? item)
              (exact-integer/exact? item))
          (recur remaining next-entries next-characters)

          (and (record? item) (not allow-records?))
          false

          (map? item)
          (recur
           (reduce-kv
            (fn [items k v]
              (conj items [k (inc depth)] [v (inc depth)]))
            remaining
            item)
           next-entries
           next-characters)

          ;; Canonical transport renders every sequential value as a vector,
          ;; and Clojure equality also equates lists with vectors. Accept only
          ;; the canonical sequential representation so a custom codec cannot
          ;; assign different authority to values that collide as cache keys.
          (or (set? item) (vector? item))
          (recur (into remaining
                       (map #(vector % (inc depth)))
                       item)
                 next-entries
                 next-characters)

          :else
          false)))))

(defn ^:no-doc cursor-cache-data?
  "Internal guard for canonical cursor identities. Records are rejected
  because canonical transport turns every map-like record into a plain map."
  [value]
  (try
    (metadata-free-portable-data?
     value false
     {:maximum-depth secure/default-maximum-depth
      :maximum-entries secure/default-maximum-entries
      :maximum-characters secure/default-maximum-size})
    (catch #?(:clj Throwable :cljs :default) _ false)))

(defn- canonical-integer-representation?
  [value]
  (and
   (exact-integer/exact? value)
   #?(:clj (instance? Long value)
      :cljs
      ;; Canonical text and JavaScript key equality both collapse -0 into 0.
      ;; Admit only the representation produced by canonical EDN decoding.
      (or (not (zero? value))
          (pos? (/ 1 value))))))

(defn ^:no-doc canonical-cursor-identity?
  "True only when canonical transport preserves an external ID's value shape.

  This closes cache/scope aliases where Clojure equality equates different
  representations (BigInt/Long, list/vector, subvec/vector, or signed zero)
  that a deterministic custom codec may distinguish. Map/set IDs are rejected
  because their comparator/implementation representation is not portable."
  [value]
  (and
   ;; Exact cache keys are intentionally much smaller than the general cursor
   ;; envelope. Oversized IDs remain usable on ordinary internalized paths but
   ;; never make a caller-controlled hash/traversal part of a hot cache probe.
   (metadata-free-portable-data?
    value false
    {:maximum-depth secure/default-maximum-depth
     :maximum-entries 1024
     :maximum-characters 4096})
   (letfn [(canonical? [item]
             (cond
               (integer? item)
               (canonical-integer-representation? item)

               (vector? item)
               ;; Avoid building a canonical copy of the common vector/string
               ;; ID path on every exact transport hit.
               (and (= (type item) (type []))
                    (every? canonical? item))

               (or (map? item) (set? item))
               false

               :else
               (try
                 (let [canonical (secure/canonicalize item)]
                   (and (= (type item) (type canonical))
                        (= item canonical)))
                 (catch #?(:clj Throwable :cljs :default) _ false))))]
     (canonical? value))))

(def ^:private authorization-abi
  {:key-version 2
   :answer-value-version 2
   :subproblem-value-version 2
   :backend-adapter-version backend/adapter-version
   :engine-version engine/engine-version
   :compiler-plan-compatibility engine/compiler-plan-compatibility})

(def ^:private exact-basis-identity-fields
  #{:backend :source-id :branch :source-lifecycle
    :basis-kind :revision :exact-locator :backend-snapshot-id})

(def ^:private exact-basis-key-fields
  #{:key-version :backend :basis-identity
    :adapter-fingerprint :identity-contract})

(def ^:private answer-entry-fields
  #{:format :value :cache-basis :computed-revision
    :computed-exact-locator})

(def ^:private rendered-page-entry-fields
  #{:format :page})

(def ^:private initial-metrics
  {:hits 0
   :misses 0
   :puts 0
   :exact-hits 0
   :managed-hits 0
   :rendered-page-misses 0
   :rendered-page-puts 0
   :rendered-page-publication-races 0
   :rendered-page-rejections 0
   :rendered-page-store-errors 0
   :bypasses 0
   :expirations 0
   :restores 0
   :stamp-failures 0
   :retention-ineligible-pages 0
   :proof-unavailable 0
   :proof-unavailable-reasons {}
   :proof-contract-violations 0
   :proof-contract-violation-reasons {}})

(def cache-option-keys
  "Closed public configuration surface for authorization caching."
  #{:max-entries :denotation-max-entries :telemetry?})

(def ^:private internal-cache-option-keys
  (conj cache-option-keys :proof-contract-reporter :content-change-fn))

(defn- invalid-config!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/invalid-config
            :eacl/error :eacl/invalid-config}
           data))))

(defn- valid-capacity?
  [value]
  #?(:clj
     (and (integer? value)
          (pos? value)
          (<= value exact-integer/maximum))
     :cljs
     (and (number? value)
          (js/Number.isSafeInteger value)
          (pos? value))))

(defn- validate-capacity!
  [option value]
  (when-not (valid-capacity? value)
    (invalid-config!
     "Cache capacities must be positive cross-runtime safe integers."
     {:option option :value value :maximum exact-integer/maximum}))
  value)

(defn exact-basis-key
  "Returns the complete identity of one admissible immutable selected basis."
  [adapter basis-identity]
  (when (and (cache-key/closed-map-fields?
              basis-identity exact-basis-identity-fields)
             (backend/admissible-basis-kind? (:basis-kind basis-identity)))
    {:key-version 2
     :backend (backend/backend-id adapter)
     :basis-identity
     {:backend (:backend basis-identity)
      :source-id (:source-id basis-identity)
      :branch (:branch basis-identity)
      :source-lifecycle (:source-lifecycle basis-identity)
      :basis-kind (:basis-kind basis-identity)
      :revision (:revision basis-identity)
      :exact-locator (:exact-locator basis-identity)
      :backend-snapshot-id (:backend-snapshot-id basis-identity)}
     :adapter-fingerprint (backend/fingerprint adapter)
     :identity-contract (backend/identity-contract adapter)}))

(defn- valid-exact-basis-key?
  [basis-key]
  (let [identity (:basis-identity basis-key)]
    (and (cache-key/closed-map-fields? basis-key exact-basis-key-fields)
         (= 2 (:key-version basis-key))
         (keyword? (:backend basis-key))
         (cache-key/closed-map-fields?
          identity exact-basis-identity-fields)
         (= (:backend basis-key) (:backend identity))
         (backend/admissible-basis-kind? (:basis-kind identity))
         (some? (:source-id identity))
         (some? (:source-lifecycle identity))
         (proof-frame/generation? (:revision identity))
         (causal-token/exact-locator? (:exact-locator identity))
         (map? (:backend-snapshot-id identity))
         (some? (:adapter-fingerprint basis-key))
         (keyword? (:identity-contract basis-key)))))

(defn- canonical-lineage
  [lineage]
  (when (and (map? lineage)
             (= #{:source-scope :source-lifecycle}
                (set (keys lineage)))
             (map? (:source-scope lineage))
             (= #{:backend :source-id :branch}
                (set (keys (:source-scope lineage))))
             (keyword? (get-in lineage [:source-scope :backend]))
             (some? (get-in lineage [:source-scope :source-id]))
             (some? (:source-lifecycle lineage)))
    {:source-scope
     (select-keys (:source-scope lineage) [:backend :source-id :branch])
     :source-lifecycle (:source-lifecycle lineage)}))

(defn- exact-source-identity
  [basis-key]
  (let [basis (:basis-identity basis-key)]
    {:lineage
     {:source-scope {:backend (:backend basis)
                     :source-id (:source-id basis)
                     :branch (:branch basis)}
      :source-lifecycle (:source-lifecycle basis)}
     :adapter-fingerprint (:adapter-fingerprint basis-key)
     :identity-contract (:identity-contract basis-key)}))

(def ^:private managed-source-identity-fields
  #{:lineage :adapter-fingerprint :identity-contract :authorization-abi})

(defn managed-source-identity
  "Builds the complete source identity shared by proof-managed cache keys.

  Unlike an exact basis key, this identity deliberately contains no selected
  snapshot locator. That lets a speculative basis consult an older committed
  proof without pretending the speculative basis itself is exact-cacheable."
  [lineage adapter-fingerprint identity-contract]
  (when-let [lineage (and (some? adapter-fingerprint)
                          (keyword? identity-contract)
                          (canonical-lineage lineage))]
    {:lineage lineage
     :adapter-fingerprint adapter-fingerprint
     :identity-contract identity-contract
     :authorization-abi authorization-abi}))

(defn- valid-managed-source-identity?
  [source]
  (and (map? source)
       (= managed-source-identity-fields (set (keys source)))
       (= authorization-abi (:authorization-abi source))
       (some? (:adapter-fingerprint source))
       (keyword? (:identity-contract source))
       (= (:lineage source)
          (canonical-lineage (:lineage source)))))

(defn- exact-answer-key
  [basis-key semantic-key]
  (cache-key/exact-answer-key
   {:tier :answer
    :source-lifecycle (exact-source-identity basis-key)
    :abi authorization-abi
    :semantic [(:operation semantic-key) semantic-key]
    :reuse basis-key}))

(defn- exact-rendered-page-key
  [basis-key semantic-key]
  (cache-key/exact-answer-key
   {:tier :rendered-page
    :source-lifecycle (exact-source-identity basis-key)
    :abi authorization-abi
    :semantic [(:operation semantic-key) semantic-key]
    :reuse basis-key}))

(defn- managed-answer-key
  [source-identity descriptor semantic-key]
  (when (and source-identity (proof-frame/descriptor? descriptor))
    (cache-key/managed-answer-key
     {:tier :answer
      :source-lifecycle source-identity
      :abi authorization-abi
      :semantic [(:operation semantic-key) semantic-key]
      :reuse descriptor})))

(defn- managed-source-from-exact-basis
  [basis-key]
  (let [{:keys [lineage adapter-fingerprint identity-contract]}
        (exact-source-identity basis-key)]
    (managed-source-identity
     lineage adapter-fingerprint identity-contract)))

(defn- exact-denotation-key-fn
  [basis-key]
  ;; The source identity is a function of the basis key alone; build it once
  ;; per request rather than once per candidate key.
  (let [source-lifecycle (exact-source-identity basis-key)]
    (fn [semantic-key]
      (cache-key/exact-denotation-key
       {:tier :denotation
        :source-lifecycle source-lifecycle
        :abi authorization-abi
        :semantic semantic-key
        :reuse basis-key}))))

(defn- lifecycle-content-change
  [lifecycle-ref token content-change-fn]
  (fn []
    (loop []
      (let [current @lifecycle-ref]
        (when (identical? token (:token current))
          (let [next (update current :content-revision inc)]
            (if (compare-and-set! lifecycle-ref current next)
              ;; This callback is deliberately outside both cache mutation
              ;; transform and this lifecycle CAS. It runs exactly once only
              ;; after the captured inner lifecycle accepted the change.
              (when content-change-fn
                (content-change-fn))
              (recur))))))))

(defn- new-lifecycle
  [lifecycle-ref subproblem-options content-revision content-change-fn]
  (let [token (atom nil)]
    (->CacheLifecycle
     token
     (subproblem/store
      subproblem-options
      (lifecycle-content-change
       lifecycle-ref token content-change-fn))
     (lru/store (:answer-max-entries subproblem-options))
     content-revision)))

(defn- restored-lifecycle
  [lifecycle-ref restored-store subproblem-options content-revision
   content-change-fn]
  (let [token (atom nil)]
    (->CacheLifecycle
     token
     (assoc restored-store
            :content-revision
            (lifecycle-content-change
             lifecycle-ref token content-change-fn))
     ;; Rendered pages contain process-local public projections and cursor
     ;; construction inputs. Portable snapshots deliberately carry only the
     ;; internal semantic answer/denotation entries, so restore starts this
     ;; exact-only derived store empty.
     (lru/store (:answer-max-entries subproblem-options))
     content-revision)))

(defn- replace-lifecycle!
  [store make-next]
  (loop []
    (let [current @(:lifecycle store)
          next (make-next (inc (:content-revision current)))]
      (if (compare-and-set! (:lifecycle store) current next)
        next
        (recur)))))

(defn basis-cache
  "Creates one client-private flat authorization cache.

  `:max-entries` is the completed-answer capacity. Exact-denotation
  capacity may be set independently with `:denotation-max-entries`. Removed
  nested, projection, weight, admission, and generation options fail closed."
  ([]
   (basis-cache {}))
  ([{:keys [max-entries denotation-max-entries telemetry?
            proof-contract-reporter content-change-fn]
     :or {max-entries 1024
          telemetry? true}
     :as options}]
   (let [unknown (seq (remove internal-cache-option-keys
                              (keys options)))]
     (when unknown
       (invalid-config!
        "EACL cache configuration contains unknown options."
        {:key :cache
         :unknown-keys (vec unknown)
         :known-keys (vec (sort cache-option-keys))})))
   (validate-capacity! :max-entries max-entries)
   (when-not (boolean? telemetry?)
     (invalid-config!
      "EACL cache :telemetry? must be boolean."
      {:key :telemetry? :value telemetry?}))
   (when-not (or (nil? content-change-fn) (fn? content-change-fn))
     (invalid-config!
      "EACL internal cache content-change hook must be a function."
      {:key :content-change-fn :value content-change-fn}))
   (let [subproblem-options
         (cond-> {:answer-max-entries max-entries
                  :telemetry? telemetry?}
           (contains? options :denotation-max-entries)
           (assoc :denotation-max-entries denotation-max-entries))
         lifecycle-ref (atom nil)
         _ (reset! lifecycle-ref
                   (new-lifecycle
                    lifecycle-ref subproblem-options 0 content-change-fn))]
     (->BasisCache
      lifecycle-ref
      (atom initial-metrics)
      subproblem-options
      (atom false)
      (atom #{})
      proof-contract-reporter
      telemetry?
      #?(:clj (LongAdder.) :cljs nil)
      #?(:clj (LongAdder.) :cljs nil)
      #?(:clj (LongAdder.) :cljs (volatile! 0))
      content-change-fn))))

(defn basis-cache?
  [value]
  (instance? BasisCache value))

(defn- record-metrics!
  [store f & args]
  (when (:telemetry-enabled? store)
    (apply swap! (:metrics store) f args))
  nil)

(defn- record-rendered-page-hit!
  [store]
  (when (:telemetry-enabled? store)
    #?(:clj (.increment ^LongAdder (:rendered-page-hits store))
       :cljs (vswap! (:rendered-page-hits store) inc)))
  nil)

(defn- record-answer-hit!
  [store tier]
  (when (:telemetry-enabled? store)
    #?(:clj
       (let [^LongAdder counter
             (if (= tier :exact-basis)
               (:exact-hits store)
               (:managed-hits store))]
         (.increment counter))
       :cljs
       (record-metrics!
        store
        (fn [metrics]
          (-> metrics
              (update :hits inc)
              (update (if (= tier :exact-basis)
                        :exact-hits
                        :managed-hits)
                      inc))))))
  nil)

(defn- invalid-cache-option!
  [message value data]
  (invalid-config!
   message
   (merge {:key :cache :value value} data)))

(defn basis-cache-for-option
  "Normalizes the public `:cache` option into a private store or nil."
  ([value]
   (basis-cache-for-option value {}))
  ([value {:keys [proof-contract-reporter content-change-fn]}]
   (cond
     (no-cache? value) nil
     (nil? value)
     (basis-cache {:proof-contract-reporter proof-contract-reporter
                   :content-change-fn content-change-fn})

     (basis-cache? value)
     (invalid-cache-option!
      "A basis cache is owned by exactly one EACL client."
      value {:reason :client-private-cache-reuse})

     (record? value)
     (invalid-cache-option!
      "Application cache stores cannot control authorization state."
      value {:reason :unsupported-provider-store})

     (map? value)
     (let [unknown (seq (remove cache-option-keys (keys value)))]
       (when (contains? value :store)
         (invalid-cache-option!
          "Nested cache adapters are not supported."
          value {:reason :unsupported-provider-store}))
       (when unknown
         (invalid-cache-option!
          "EACL cache configuration contains unknown options."
          value
          {:reason :unknown-cache-options
           :unknown-keys (vec unknown)
           :known-keys (vec (sort cache-option-keys))}))
       (basis-cache
        (assoc value
               :proof-contract-reporter proof-contract-reporter
               :content-change-fn content-change-fn)))

     :else
     (invalid-cache-option!
      "EACL :cache must be a configuration map or eacl.cache/no-cache."
      value {:reason :unsupported-provider-store}))))

(defn- composite-key-mode
  [key]
  (when (and (vector? key)
             (= 3 (count key))
             (= cache-key/key-format (first key))
             (vector? (nth key 2))
             (<= 2 (count (nth key 2))))
    (second (nth key 2))))

(defn basis-cache-stats
  [store]
  (when-not (basis-cache? store)
    (invalid-config! "Expected an EACL basis cache." {:cache store}))
  (let [subproblem-store (:subproblems @(:lifecycle store))
        subproblem-stats (subproblem/stats subproblem-store)
        entries
        (mapcat #(subproblem/resident-tier-entries subproblem-store %)
                [:answer :denotation])
        mode-counts (frequencies (keep (comp composite-key-mode :key) entries))
        metrics @(:metrics store)
        exact-hits
        #?(:clj (.sum ^LongAdder (:exact-hits store))
           :cljs (:exact-hits metrics))
        managed-hits
        #?(:clj (.sum ^LongAdder (:managed-hits store))
           :cljs (:managed-hits metrics))]
    (assoc metrics
           :hits (+ exact-hits managed-hits)
           :exact-hits exact-hits
           :managed-hits managed-hits
           :telemetry-enabled? (:telemetry-enabled? store)
           :managed-lifting-disabled?
           @(:managed-lifting-disabled? store)
           :entries (count entries)
           :exact-entries (get mode-counts :exact 0)
           :managed-entries (get mode-counts :managed 0)
           :rendered-page-hits
           #?(:clj (.sum ^LongAdder (:rendered-page-hits store))
              :cljs @(:rendered-page-hits store))
           :rendered-page-entries
           (lru/entry-count (:rendered-pages @(:lifecycle store)))
           :subproblems subproblem-stats)))

(defn record-current-bypass!
  [store]
  (when store
    (when-not (basis-cache? store)
      (invalid-config! "Expected an EACL basis cache." {:cache store}))
    (record-metrics! store update :bypasses inc))
  nil)

(defn record-proof-unavailable!
  [store {:keys [reason]}]
  (when store
    (when-not (basis-cache? store)
      (invalid-config! "Expected an EACL basis cache." {:cache store}))
    (record-metrics!
     store
     (fn [metrics]
       (-> metrics
           (update :proof-unavailable inc)
           (update-in [:proof-unavailable-reasons reason] (fnil inc 0))))))
  nil)

(defn- claim-once!
  [claimed value]
  (loop []
    (let [current @claimed]
      (cond
        (contains? current value) false
        (compare-and-set! claimed current (conj current value)) true
        :else (recur)))))

(defn record-proof-diagnostic!
  "Records typed proof diagnostics and fails managed reuse closed on a
  provider contract violation. Reporter failures never affect authorization."
  [store {:keys [status reason] :as diagnostic}]
  (when store
    (when-not (basis-cache? store)
      (invalid-config! "Expected an EACL basis cache." {:cache store}))
    (case status
      :unavailable
      (record-proof-unavailable! store diagnostic)

      :contract-violation
      (do
        (reset! (:managed-lifting-disabled? store) true)
        (record-metrics!
         store
         (fn [metrics]
           (-> metrics
               (update :proof-contract-violations inc)
               (update-in [:proof-contract-violation-reasons reason]
                          (fnil inc 0)))))
        (when (and (:proof-contract-reporter store)
                   (claim-once!
                    (:reported-contract-violations store) reason))
          (try
            ((:proof-contract-reporter store) diagnostic)
            (catch #?(:clj Throwable :cljs :default) _)))
        nil)

      nil))
  nil)

(defn capture-cache-lifecycle
  "Captures the immutable outer authorization-cache lifecycle for one request."
  [store]
  (when store
    (when-not (basis-cache? store)
      (invalid-config! "Expected an EACL basis cache." {:cache store}))
    @(:lifecycle store)))

(defn cache-content-revision
  [store]
  (when-not (basis-cache? store)
    (invalid-config! "Expected an EACL basis cache." {:cache store}))
  (:content-revision @(:lifecycle store)))

(defn- valid-bounds!
  [bounds]
  (when-not (map? bounds)
    (invalid-config! "Cache snapshot bounds must be a map." {:bounds bounds}))
  (let [unknown (seq (remove #{:max-entries} (keys bounds)))
        max-entries (:max-entries bounds)]
    (when unknown
      (invalid-config!
       "Cache snapshot bounds contain removed or unknown options."
       {:bounds bounds :unknown-keys (vec unknown)}))
    (validate-capacity! :max-entries max-entries)
    max-entries))

(defn- canonical-entry-token
  [{:keys [tier key]}]
  ;; Live semantic keys are not byte-capped. The host that serializes this
  ;; trusted snapshot value owns the authenticated encoded-byte bound.
  (secure/encode-canonical
   [tier key]
   {:maximum-size secure/maximum-safe-integer
    :maximum-depth 64
    :maximum-entries 131072}))

(declare snapshot-entry-valid?)

(defn export-basis-snapshot
  "Exports deterministic flat entries and no LRU-private policy state."
  [store bounds]
  (when-not (basis-cache? store)
    (invalid-config! "Expected an EACL basis cache." {:cache store}))
  (let [max-entries (valid-bounds! bounds)
        subproblem-store (:subproblems @(:lifecycle store))
        entries
        (->> [:answer :denotation]
             (mapcat #(subproblem/resident-tier-entries
                       subproblem-store %))
             ;; A managed hit may be promoted into the process-local exact
             ;; LRU. Its causal origin is established by that live transition,
             ;; but portable snapshots deliberately carry no policy/provenance
             ;; sidecar. Export only entries whose complete key/value envelope
             ;; is independently restorable; the managed mapping remains and
             ;; can be promoted again after restore.
             (filter snapshot-entry-valid?)
             ;; Decorate-sort: the canonical token is computed once per
             ;; entry instead of per comparison.
             (map (fn [entry] [(canonical-entry-token entry) entry]))
             (sort-by first compare)
             (map second)
             (take max-entries)
             vec)]
    {:format basis-snapshot-format
     :entries entries
     :entry-count (count entries)}))

(defn- incompatible-snapshot!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/incompatible-cache-snapshot
            :eacl/error :eacl/incompatible-cache-snapshot
            :expected-format basis-snapshot-format}
           data))))

(defn- authorization-composite-key?
  [tier key]
  (when (and (vector? key)
             (= 3 (count key))
             (= cache-key/key-format (first key))
             (= (if (= tier :answer)
                  :authorization-answer
                  :authorization-subproblem)
                (second key))
             (vector? (nth key 2))
             (= 6 (count (nth key 2))))
    (let [[key-tier mode source abi semantic reuse] (nth key 2)]
      (and (= tier key-tier)
           (= authorization-abi abi)
           (some? semantic)
           (case mode
             :exact
             (and (valid-exact-basis-key? reuse)
                  (= source (exact-source-identity reuse)))

             :managed
             (and (= :answer tier)
                  (proof-frame/descriptor? reuse)
                  (valid-managed-source-identity? source))

             false)))))

(defn- portable-natural?
  [value]
  (and #?(:clj (integer? value)
          :cljs (and (number? value)
                     (js/Number.isSafeInteger value)))
       (<= 0 value exact-integer/maximum)))

(def ^:private page-answer-fields
  #{:data :page-info})

(def ^:private optional-page-answer-fields
  "Internal page fields that range reuse retains alongside the public
  shape: one internal edge per result and the route's reuse marker."
  #{:edges :range-reusable?})

(def ^:private required-page-info-fields
  #{:start-cursor :end-cursor
    :has-next-page? :has-previous-page?})

(def ^:private allowed-page-info-fields
  (conj required-page-info-fields :bounded?))

(defn- internal-page-edge?
  [value]
  (or (nil? value) (map? value)))

(defn- page-answer?
  [value]
  (let [page-info (:page-info value)
        page-info-fields (when (map? page-info) (set (keys page-info)))]
    (and (map? value)
         (= page-answer-fields
            (set (remove optional-page-answer-fields (keys value))))
         (vector? (:data value))
         (or (not (contains? value :edges))
             (and (vector? (:edges value))
                  (= (count (:edges value)) (count (:data value)))
                  (every? map? (:edges value))))
         (or (not (contains? value :range-reusable?))
             (boolean? (:range-reusable? value)))
         (map? page-info)
         (every? page-info-fields required-page-info-fields)
         (every? allowed-page-info-fields page-info-fields)
         (internal-page-edge? (:start-cursor page-info))
         (internal-page-edge? (:end-cursor page-info))
         (boolean? (:has-next-page? page-info))
         (boolean? (:has-previous-page? page-info))
         (or (not (contains? page-info :bounded?))
             (boolean? (:bounded? page-info))))))

(defn- count-answer?
  [semantic-key value]
  (let [query (:query semantic-key)
        answer-query (or (:internal query) (:public query))
        limited? (and (map? answer-query)
                      (contains? answer-query :count-limit))
        expected-limit (if limited?
                         (:count-limit answer-query)
                         -1)
        detailed? (= :detailed (:result-policy answer-query))
        expected-fields (cond-> #{:count :limit}
                          limited? (conj :truncated?)
                          detailed? (conj :definite-count :conditional-count))
        count-value (:count value)]
    (and (map? query)
         (map? answer-query)
         (map? value)
         (= expected-fields (set (keys value)))
         (portable-natural? count-value)
         (or (not detailed?)
             (and (portable-natural? (:definite-count value))
                  (portable-natural? (:conditional-count value))
                  (= count-value (+ (:definite-count value)
                                    (:conditional-count value)))))
         (if limited?
           (and (portable-natural? expected-limit)
                (= expected-limit (:limit value))
                (boolean? (:truncated? value))
                (<= count-value expected-limit)
                (or (false? (:truncated? value))
                    (= count-value expected-limit)))
           (= -1 (:limit value))))))

(def ^:private spice-object-fields
  #{:type :id :relation})

(defn- unqualified-keyword?
  [value]
  (and (keyword? value) (nil? (namespace value))))

(defn- rendered-spice-object-shape?
  [value]
  (and (map? value)
       (= spice-object-fields (set (keys value)))
       (unqualified-keyword? (:type value))
       (some? (:id value))
       (canonical-cursor-identity? (:id value))
       (or (nil? (:relation value))
           (unqualified-keyword? (:relation value)))))

(defn- rendered-relationship-shape?
  [value]
  (and (map? value)
       (every? (into #{:subject :relation :resource} relationship-mutations/qualifier-keys) (keys value))
       (relationship-mutations/canonical-qualifier-metadata? value)
       (rendered-spice-object-shape? (:subject value))
       (unqualified-keyword? (:relation value))
       (rendered-spice-object-shape? (:resource value))))

(defn- internal-relationship-shape?
  "Native eids may be Integer or Long. Their representation is an adapter
  detail; public transport IDs retain the stricter canonical shape check."
  [value]
  (and (every? #(let [id (get-in value [% :id])]
                  (and (integer? id) (pos? id)
                       (<= id #?(:clj Long/MAX_VALUE :cljs js/Number.MAX_SAFE_INTEGER))))
               [:subject :resource])
       (rendered-relationship-shape?
        #?(:clj (-> value (update-in [:subject :id] long) (update-in [:resource :id] long))
           :cljs value))))

(defn rendered-page-entry-valid?
  "True for one exact public page with its already-authenticated cursor tokens.

  The store is process-local and entries are keyed by the complete raw request
  plus exact basis. Cursor TTL disables this tier, so a hit can return the
  immutable transport page without rebuilding or reauthenticating cursors.
  Validation runs only on publication, never on a hit."
  ([value]
   (rendered-page-entry-valid? nil value))
  ([semantic-key value]
   (let [page (:page value)
         page-info (:page-info page)
         page-info-fields (when (map? page-info) (set (keys page-info)))
         operation (:operation semantic-key)
         qualified? (if semantic-key (some? (:qualification semantic-key))
                        (contains? value :qualification-certificate))
         detailed? (= :detailed (get-in semantic-key [:query :public :result-policy]))
         rendered-item?
         (case operation
           :read-relationships rendered-relationship-shape?
           (:lookup-resources :lookup-subjects)
           (if detailed?
             #(authorization-result/lookup-result-valid? rendered-spice-object-shape? %)
             rendered-spice-object-shape?)
          ;; The one-argument public predicate accepts either supported page
          ;; shape; publication always supplies the operation-specific key.
           (fn [item]
             (or (rendered-spice-object-shape? item)
                 (rendered-relationship-shape? item))))
         token? (fn [candidate]
                  (or (nil? candidate)
                      (and (string? candidate)
                           (pos? (count candidate)))))]
     (and (map? value)
          (= (cond-> rendered-page-entry-fields qualified? (conj :qualification-certificate))
             (set (keys value)))
          (or (not qualified?)
              (and (or (nil? semantic-key)
                       (= temporal/collection-format (:qualification-certificate-format semantic-key)))
                   (temporal/interval-valid? (:qualification-certificate value))))
          (= rendered-page-entry-format (:format value))
          (map? page)
          (= page-answer-fields (set (keys page)))
          (vector? (:data page))
          (<= (count (:data page)) 1000)
          (every? rendered-item? (:data page))
          (map? page-info)
          (every? page-info-fields required-page-info-fields)
          (every? allowed-page-info-fields page-info-fields)
          (token? (:start-cursor page-info))
          (token? (:end-cursor page-info))
          (boolean? (:has-next-page? page-info))
          (boolean? (:has-previous-page? page-info))
          (or (not (contains? page-info :bounded?))
              (boolean? (:bounded? page-info)))))))

(defn- permission-tree-answer?
  [value]
  (loop [pending [value]]
    (if-let [node (peek pending)]
      (let [remaining (pop pending)
            fields (when (map? node) (set (keys node)))
            base-valid?
            (and (map? node)
                 (rendered-spice-object-shape? (:expanded-object node))
                 (unqualified-keyword? (:expanded-relation node)))]
        (cond
          (= #{:expanded-object :expanded-relation :leaf} fields)
          (let [leaf (:leaf node)]
            (and base-valid?
                 (map? leaf)
                 (= #{:subjects} (set (keys leaf)))
                 (vector? (:subjects leaf))
                 (every? rendered-spice-object-shape? (:subjects leaf))
                 (recur remaining)))

          (= #{:expanded-object :expanded-relation :intermediate} fields)
          (let [intermediate (:intermediate node)
                operation (:operation intermediate)
                children (:children intermediate)]
            (and base-valid?
                 (map? intermediate)
                 (= #{:operation :children} (set (keys intermediate)))
                 (contains? #{:union :intersection :exclusion} operation)
                 (vector? children)
                 (or (not= :exclusion operation)
                     (= 2 (count children)))
                 (recur (into remaining children))))

          :else false))
      true)))

(defn- lookup-page-answer?
  [semantic-key value]
  (let [query (:query semantic-key)
        detailed? (= :detailed (:result-policy (or (:internal query) (:public query))))]
    (and (page-answer? value)
         (if detailed?
           (every? #(authorization-result/lookup-result-valid? rendered-spice-object-shape? %)
                   (:data value))
           (not-any? #(and (map? %) (contains? % :object)) (:data value))))))

(defn completed-answer-value-valid?
  "Validates one completed authorization answer against its semantic key.

  The predicate guards supported cache ingress: completed publication and
  snapshot restore. Exact resident lookup is ordinary membership by a complete
  semantic key, so an already accepted value is not validated again per hit."
  [operation semantic-key value]
  (let [qualified-result? (and (:qualification semantic-key)
                               (contains? #{:lookup-resources :lookup-subjects :count-resources :count-subjects :read-relationships} operation))
        certificate (:qualification-certificate value)
        value (if qualified-result? (dissoc value :qualification-certificate) value)]
    (and (or (not qualified-result?)
             (and (= temporal/collection-format (:qualification-certificate-format semantic-key))
                  (temporal/interval-valid? certificate)))
         (map? semantic-key)
         (= operation (:operation semantic-key))
         (case operation
           :can? (cond
                   (= temporal/point-format (:temporal-answer-format semantic-key))
                   (and (:qualification semantic-key) (temporal/point-answer-valid? value))
                   (:qualification semantic-key) (authorization-result/cache-value? value)
                   :else (boolean? value))
           :read-relationships (and (page-answer? value)
                                    (or (nil? (:qualification semantic-key))
                                        (every? internal-relationship-shape? (:data value))))
           :lookup-resources (lookup-page-answer? semantic-key value)
           :lookup-subjects (lookup-page-answer? semantic-key value)
           :count-resources (count-answer? semantic-key value)
           :count-subjects (count-answer? semantic-key value)
           :expand-permission-tree (permission-tree-answer? value)
           false))))

(defn- answer-snapshot-entry-valid?
  [key entry]
  (let [[_ mode _ _ semantic reuse] (nth key 2)
        [operation semantic-key] semantic
        computed-revision (:computed-revision entry)]
    (and (vector? semantic)
         (= 2 (count semantic))
         (keyword? operation)
         (map? entry)
         (= answer-entry-fields (set (keys entry)))
         (= answer-entry-format (:format entry))
         (map? (:cache-basis entry))
         (proof-frame/generation? computed-revision)
         (causal-token/exact-locator? (:computed-exact-locator entry))
         (completed-answer-value-valid?
          operation semantic-key (:value entry))
         (case mode
           :exact
           ;; Portable exact entries must be direct computations for that
           ;; complete immutable basis. Process-local managed promotions are
           ;; intentionally omitted from export because their validating
           ;; transition is not snapshot policy state.
           (and (= (:cache-basis entry)
                   (get-in reuse
                           [:basis-identity :backend-snapshot-id]))
                (= computed-revision
                   (get-in reuse [:basis-identity :revision]))
                (= (:computed-exact-locator entry)
                   (get-in reuse [:basis-identity :exact-locator])))

           :managed
           (and (<= (:schema-generation reuse) computed-revision)
                (<= (:dependency-stamp reuse) computed-revision))

           false))))

(defn- denotation-value-shape?
  [semantic value]
  (let [operation (when (vector? semantic) (first semantic))]
    (and (contains? #{:operator-acyclic-point
                      :operator-recursive-point}
                    operation)
         (boolean? value))))

(defn- subproblem-snapshot-entry-valid?
  [tier key value]
  (let [[_ mode _ _ semantic _reuse] (nth key 2)]
    (and (= :denotation tier)
         (= :exact mode)
         (denotation-value-shape? semantic value))))

(defn- snapshot-entry-valid?
  [{:keys [tier key value]}]
  (and (authorization-composite-key? tier key)
       (if (= :answer tier)
         (answer-snapshot-entry-valid? key value)
         (subproblem-snapshot-entry-valid? tier key value))))

(defn- validate-basis-snapshot!
  [snapshot max-entries]
  (when-not (map? snapshot)
    (incompatible-snapshot! "Cache snapshot must be a map."
                            {:snapshot snapshot}))
  (when-not (= basis-snapshot-format (:format snapshot))
    (incompatible-snapshot!
     "Cache snapshot format is not supported."
     {:actual-format (:format snapshot)}))
  (when-not (= #{:format :entries :entry-count} (set (keys snapshot)))
    (incompatible-snapshot!
     "Cache snapshot shape is not closed."
     ;; Snapshot keys are untrusted input and need not be mutually comparable.
     ;; Diagnostics must preserve the typed restore boundary rather than
     ;; leaking a host comparison exception for a heterogeneous extra key.
     {:keys (vec (keys snapshot))}))
  (let [entries (:entries snapshot)]
    (when-not (and (vector? entries)
                   (proof-frame/generation? (:entry-count snapshot))
                   (= (count entries) (:entry-count snapshot))
                   (<= (count entries) max-entries))
      (incompatible-snapshot!
       "Cache snapshot entry count is invalid."
       {:entry-count (:entry-count snapshot)
        :actual-entry-count (when (vector? entries) (count entries))
        :max-entries max-entries}))
    (doseq [entry entries]
      (when-not (and (map? entry)
                     (= #{:tier :key :value} (set (keys entry)))
                     (contains? #{:denotation :answer}
                                (:tier entry))
                     (authorization-composite-key?
                      (:tier entry) (:key entry)))
        (incompatible-snapshot!
         "Cache snapshot entry is invalid."
         {:entry entry})))
    (when-not (= (count entries)
                 (count (set (map (juxt :tier :key) entries))))
      (incompatible-snapshot! "Cache snapshot contains duplicate keys." {}))))

(defn restore-basis-snapshot!
  "Validates and reconstructs fresh stores off-side, then installs atomically."
  [store snapshot bounds]
  (when-not (basis-cache? store)
    (invalid-config! "Expected an EACL basis cache." {:cache store}))
  (let [max-entries (valid-bounds! bounds)
        _ (validate-basis-snapshot! snapshot max-entries)
        restored
        (try
          (subproblem/restore-store
           {:format subproblem/snapshot-format
            :entries (:entries snapshot)
            :entry-count (:entry-count snapshot)}
           (:subproblem-options store)
           nil
           {:entry-valid? snapshot-entry-valid?})
          (catch #?(:clj Throwable :cljs :default) error
            (if (= :eacl/cache-snapshot-incompatible
                   (:type (ex-data error)))
              (incompatible-snapshot!
               "Cache snapshot entry validation failed."
               {:cause-type (:type (ex-data error))})
              (throw error))))]
    (replace-lifecycle!
     store
     #(restored-lifecycle
       (:lifecycle store) restored (:subproblem-options store) %
       (:content-change-fn store)))
    (reset! (:managed-lifting-disabled? store) false)
    (reset! (:reported-contract-violations store) #{})
    (record-metrics! store update :restores inc)
    {:restored? true
     :entry-count (:entry-count snapshot)}))

(defn- safe-valid?
  [valid? value]
  (try
    (boolean (valid? value))
    (catch #?(:clj Throwable :cljs :default) _ false)))

(defn- completed-entry
  [value cache-basis revision exact-locator]
  {:format answer-entry-format
   :value value
   :cache-basis cache-basis
   :computed-revision revision
   :computed-exact-locator exact-locator})

(defn- answer-entry-valid?
  [entry]
  (and (map? entry)
       (= answer-entry-fields (set (keys entry)))
       (= answer-entry-format (:format entry))
       (map? (:cache-basis entry))
       (proof-frame/generation? (:computed-revision entry))
       (causal-token/exact-locator? (:computed-exact-locator entry))))

(defn- managed-answer-causally-eligible?
  [requested-revision entry]
  (and (proof-frame/generation? requested-revision)
       (proof-frame/generation? (:computed-revision entry))
       (<= (:computed-revision entry) requested-revision)))

(defn- managed-descriptor
  [store managed-key-fn]
  (when (and managed-key-fn
             (not @(:managed-lifting-disabled? store)))
    (try
      (let [descriptor (managed-key-fn)]
        (when (proof-frame/descriptor? descriptor)
          descriptor))
      (catch #?(:clj Throwable :cljs :default) _
        (record-metrics! store update :stamp-failures inc)
        nil))))

(defn- lookup-answer
  [subproblem-store key eligible?]
  (some-> (if eligible?
            (subproblem/lookup-eligible! subproblem-store :answer key eligible?)
            (subproblem/lookup! subproblem-store :answer key))
          :value))

(defn- lookup-managed-answer
  [subproblem-store key requested-revision temporal? evaluation-time-ms]
  (some-> (subproblem/lookup-eligible!
           subproblem-store :answer key
           #(and (managed-answer-causally-eligible? requested-revision %)
                 (or (not temporal?) (temporal/answer-reusable? (:value %) evaluation-time-ms false))))
          :value))

(defn- record-publication!
  [store publication]
  (case (:reason publication)
    :published (record-metrics! store update :puts inc)
    :page-too-large
    (record-metrics! store update :retention-ineligible-pages inc)
    nil)
  publication)

(defn- publish-answer!
  [store subproblem-store key entry]
  (record-publication!
   store
   (subproblem/publish!
    subproblem-store :answer key
    (cond-> {:valid? answer-entry-valid?}
      (some? (temporal/answer-interval (:value entry)))
      (assoc :replace? (fn [prior next]
                         (and (= (:computed-revision prior) (:computed-revision next))
                              (= (:computed-exact-locator prior) (:computed-exact-locator next))
                              (temporal/supersedes? (:value prior) (:value next))))))
    entry)))

(defn- cache-hit-result
  [store tier entry]
  (record-answer-hit! store tier)
  {:value (:value entry)
   :cached? true
   :cache-tier tier
   :cache-basis (:cache-basis entry)})

(defn lookup-rendered-page!
  "Looks up one validated exact immutable transport page, including tokens.

  The caller supplies the captured lifecycle so an expiry racing this request
  cannot mix an old answer store with a new rendered store. Cache failures are
  ordinary misses and never affect authorization."
  [store {:keys [exact-basis-key cache-lifecycle evaluation-time-ms]} semantic-key]
  (when (and (basis-cache? store)
             (valid-exact-basis-key? exact-basis-key)
             (map? semantic-key)
             (keyword? (:operation semantic-key))
             (execution/cache-stage-available?))
    (let [lifecycle (or cache-lifecycle @(:lifecycle store))
          storage-key (exact-rendered-page-key exact-basis-key semantic-key)
          resident
          (try
            (lru/lookup! (:rendered-pages lifecycle) storage-key)
            (catch #?(:clj Throwable :cljs :default) _
              (record-metrics! store update :rendered-page-store-errors inc)
              nil))]
      (if (and (:found? resident)
               (or (nil? (:qualification semantic-key))
                   (temporal/answer-reusable? (:value resident) evaluation-time-ms true)))
        (do
          ;; JVM hits use a striped LongAdder instead of serializing on the
          ;; ordinary metrics atom. CLJS remains single-threaded.
          (record-rendered-page-hit! store)
          {:value (:value resident)
           :cached? true
           :cache-tier :exact-rendered-page
           :cache-basis
           (get-in exact-basis-key
                   [:basis-identity :backend-snapshot-id])})
        (do
          (record-metrics! store update :rendered-page-misses inc)
          nil)))))

(defn publish-rendered-page!
  "Publishes one already-built exact transport page without owning computation."
  [store
   {:keys [exact-basis-key cache-lifecycle populate-cache?]
    :or {populate-cache? true}}
   semantic-key value]
  (cond
    (not (and (basis-cache? store)
              (valid-exact-basis-key? exact-basis-key)
              (map? semantic-key)
              (keyword? (:operation semantic-key))))
    {:published? false :reason :disabled}

    (not populate-cache?)
    {:published? false :reason :read-only}

    (not (execution/cache-stage-available?))
    {:published? false :reason :execution-unavailable}

    (not (rendered-page-entry-valid? semantic-key value))
    (do
      (record-metrics! store update :rendered-page-rejections inc)
      {:published? false :reason :invalid-value})

    ;; Publication validation may traverse as many as 1,000 rendered items.
    ;; Recheck the request after that work so a deadline or cancellation racing
    ;; validation cannot populate the cache after the request lost authority to
    ;; publish.
    (not (execution/cache-stage-available?))
    {:published? false :reason :execution-unavailable}

    :else
    (let [lifecycle (or cache-lifecycle @(:lifecycle store))
          storage-key (exact-rendered-page-key exact-basis-key semantic-key)]
      (try
        (if (or (lru/put-if-absent! (:rendered-pages lifecycle) storage-key value)
                (let [prior (lru/lookup! (:rendered-pages lifecycle) storage-key)]
                  (and (:found? prior) (temporal/supersedes? (:value prior) value)
                       (lru/replace-if! (:rendered-pages lifecycle) storage-key (:value prior) value))))
          (do
            (record-metrics! store update :rendered-page-puts inc)
            {:published? true :reason :published})
          (do
            (record-metrics!
             store update :rendered-page-publication-races inc)
            {:published? false :reason :compatible-winner}))
        (catch #?(:clj Throwable :cljs :default) _
          (record-metrics! store update :rendered-page-store-errors inc)
          {:published? false :reason :store-error})))))

(defn- uncached-compute
  [compute]
  (binding [subproblem/*store* nil
            subproblem/*exact-denotation-key-fn* nil
            subproblem/*populate?* false]
    (compute)))

(defn- compute-with-subproblems
  [subproblem-store exact-denotation-key-fn populate? compute]
  (binding [subproblem/*store* subproblem-store
            subproblem/*exact-denotation-key-fn* exact-denotation-key-fn
            subproblem/*populate?* populate?]
    (compute)))

(defn- uncached-result
  [store compute]
  (when (basis-cache? store)
    (record-metrics! store update :bypasses inc))
  {:value (uncached-compute compute)
   :cached? false
   :cache-tier nil
   :cache-basis nil})

(defn resolve-basis!
  "Resolves an ordinary request exact-first, then by one complete managed key."
  [store
   {:keys [exact-basis-key cache-lifecycle managed-key-fn populate-cache?
           populate-exact? evaluation-time-ms]
    :or {populate-cache? true
         populate-exact? true}}
   semantic-key compute]
  (if-not (and (basis-cache? store)
               (valid-exact-basis-key? exact-basis-key)
               (execution/cache-stage-available?))
    (uncached-result store compute)
    (let [revision (get-in exact-basis-key [:basis-identity :revision])
          cache-basis
          (get-in exact-basis-key [:basis-identity :backend-snapshot-id])
          operation (:operation semantic-key)
          lifecycle (or cache-lifecycle @(:lifecycle store))
          subproblem-store (:subproblems lifecycle)
          exact-key (exact-answer-key exact-basis-key semantic-key)
          temporal? (or (= temporal/point-format (:temporal-answer-format semantic-key))
                        (= temporal/collection-format (:qualification-certificate-format semantic-key)))
          exact-entry (lookup-answer subproblem-store exact-key
                                     (when temporal?
                                       #(temporal/answer-reusable? (:value %) evaluation-time-ms true)))]
      (if exact-entry
        (cache-hit-result store :exact-basis exact-entry)
        (if-not (execution/cache-stage-available?)
          (uncached-result store compute)
          (let [descriptor (managed-descriptor store managed-key-fn)
                managed-source
                (managed-source-from-exact-basis exact-basis-key)
                managed-key
                (managed-answer-key
                 managed-source descriptor semantic-key)
                managed-entry
                (when managed-key
                  (lookup-managed-answer
                   subproblem-store managed-key revision temporal? evaluation-time-ms))]
            (if managed-entry
              (do
                (when (and populate-cache? populate-exact?)
                  (publish-answer!
                   store subproblem-store exact-key managed-entry))
                (cache-hit-result store :managed-current managed-entry))
              (if-not (execution/cache-stage-available?)
                (uncached-result store compute)
                (let [exact-key-fn
                      (exact-denotation-key-fn exact-basis-key)
                      value
                      (compute-with-subproblems
                       subproblem-store exact-key-fn populate-cache? compute)
                      entry
                      (completed-entry
                       value cache-basis revision
                       (get-in exact-basis-key
                               [:basis-identity :exact-locator]))
                      retain?
                      (and populate-cache?
                           (safe-valid?
                            #(completed-answer-value-valid?
                              operation semantic-key %)
                            value))]
                  (record-metrics! store update :misses inc)
                  (when retain?
                    (when managed-key
                      (publish-answer!
                       store subproblem-store managed-key entry))
                    (when populate-exact?
                      (publish-answer!
                       store subproblem-store exact-key entry)))
                  {:value value
                   :cached? false
                   :cache-tier nil
                   :cache-basis cache-basis})))))))))

(defn resolve-managed-read-only!
  "Consults only a causally valid managed key; a miss computes without writes."
  [store
   {:keys [cache-lifecycle snapshot-order managed-source managed-key-fn evaluation-time-ms]}
   semantic-key compute]
  (if-not (and (basis-cache? store)
               (proof-frame/generation? snapshot-order)
               (valid-managed-source-identity? managed-source)
               (execution/cache-stage-available?))
    (uncached-result store compute)
    (let [lifecycle (or cache-lifecycle @(:lifecycle store))
          subproblem-store (:subproblems lifecycle)]
      (if-not (execution/cache-stage-available?)
        (uncached-result store compute)
        (let [descriptor (managed-descriptor store managed-key-fn)
              managed-key
              (managed-answer-key managed-source descriptor semantic-key)
              entry
              (when managed-key
                (lookup-managed-answer
                 subproblem-store managed-key snapshot-order
                 (= temporal/point-format (:temporal-answer-format semantic-key)) evaluation-time-ms))]
          (if entry
            (cache-hit-result store :managed-current entry)
            (uncached-result store compute)))))))
