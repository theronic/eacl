(ns eacl.cache
  "Client-private authorization caching over flat standard LRU stores.

  Exact and managed reuse are semantic key construction concerns. Storage is
  only a bounded partial map of opaque keys to immutable completed values;
  misses compute independently and publication never owns computation."
  (:require [eacl.backend.v8 :as backend]
            [eacl.cache-identity :as cache-identity]
            [eacl.cache.key :as cache-key]
            [eacl.causal-token :as causal-token]
            [eacl.engine.v8 :as engine]
            [eacl.exact-integer :as exact-integer]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]))

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

(defrecord CacheLifecycle [token subproblems content-revision])

(defrecord BasisCache
           [lifecycle metrics subproblem-options
            managed-lifting-disabled? reported-contract-violations
            proof-contract-reporter telemetry-enabled?
            content-change-fn])

(def basis-snapshot-format
  "Version identifier for flat process-neutral authorization-cache snapshots."
  :eacl.cache/basis-snapshot-v2)

(def ^:private answer-entry-format :eacl.cache/completed-answer-v2)

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

(def ^:private initial-metrics
  {:hits 0
   :misses 0
   :puts 0
   :exact-hits 0
   :managed-hits 0
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
  (when (and (map? basis-identity)
             (= exact-basis-identity-fields (set (keys basis-identity)))
             (backend/admissible-basis-kind? (:basis-kind basis-identity)))
    {:key-version 2
     :backend (backend/backend-id adapter)
     :basis-identity
     (select-keys basis-identity
                  [:backend :source-id :branch :source-lifecycle
                   :basis-kind :revision :exact-locator
                   :backend-snapshot-id])
     :adapter-fingerprint (backend/fingerprint adapter)
     :identity-contract (backend/identity-contract adapter)}))

(defn- valid-exact-basis-key?
  [basis-key]
  (let [identity (:basis-identity basis-key)]
    (and (map? basis-key)
         (= exact-basis-key-fields (set (keys basis-key)))
         (= 2 (:key-version basis-key))
         (keyword? (:backend basis-key))
         (map? identity)
         (= exact-basis-identity-fields (set (keys identity)))
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
     {:source-scope (select-keys basis [:backend :source-id :branch])
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
  (fn [semantic-key]
    (cache-key/exact-denotation-key
     {:tier :denotation
      :source-lifecycle (exact-source-identity basis-key)
      :abi authorization-abi
      :semantic semantic-key
      :reuse basis-key})))

(defn- lifecycle-content-change
  [lifecycle-ref token content-change-fn]
  (fn []
    (loop []
      (let [current @lifecycle-ref]
        (when (identical? token (:token current))
          (let [next (update current :content-revision inc)]
            (if (compare-and-set! lifecycle-ref current next)
              ;; This callback is deliberately outside both the LRU atom
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
     content-revision)))

(defn- restored-lifecycle
  [lifecycle-ref restored-store content-revision content-change-fn]
  (let [token (atom nil)]
    (->CacheLifecycle
     token
     (assoc restored-store
            :content-revision
            (lifecycle-content-change
             lifecycle-ref token content-change-fn))
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

  `:max-entries` is the completed-answer LRU capacity. Exact-denotation
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
      content-change-fn))))

(defn basis-cache?
  [value]
  (instance? BasisCache value))

(defn- record-metrics!
  [store f & args]
  (when (:telemetry-enabled? store)
    (apply swap! (:metrics store) f args))
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
        mode-counts (frequencies (keep (comp composite-key-mode :key) entries))]
    (assoc @(:metrics store)
           :telemetry-enabled? (:telemetry-enabled? store)
           :managed-lifting-disabled?
           @(:managed-lifting-disabled? store)
           :entries (count entries)
           :exact-entries (get mode-counts :exact 0)
           :managed-entries (get mode-counts :managed 0)
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
             (sort-by canonical-entry-token)
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
         (= page-answer-fields (set (keys value)))
         (vector? (:data value))
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
        internal-query (:internal query)
        limited? (and (map? internal-query)
                      (contains? internal-query :count-limit))
        expected-limit (if limited?
                         (:count-limit internal-query)
                         -1)
        expected-fields (if limited?
                          #{:count :limit :truncated?}
                          #{:count :limit})
        count-value (:count value)]
    (and (map? query)
         (map? internal-query)
         (map? value)
         (= expected-fields (set (keys value)))
         (portable-natural? count-value)
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

(defn- spice-object-shape?
  [value]
  (and (map? value)
       (= spice-object-fields (set (keys value)))
       (unqualified-keyword? (:type value))
       (some? (:id value))
       (or (nil? (:relation value))
           (unqualified-keyword? (:relation value)))))

(defn- permission-tree-answer?
  [value]
  (loop [pending [value]]
    (if-let [node (peek pending)]
      (let [remaining (pop pending)
            fields (when (map? node) (set (keys node)))
            base-valid?
            (and (map? node)
                 (spice-object-shape? (:expanded-object node))
                 (unqualified-keyword? (:expanded-relation node)))]
        (cond
          (= #{:expanded-object :expanded-relation :leaf} fields)
          (let [leaf (:leaf node)]
            (and base-valid?
                 (map? leaf)
                 (= #{:subjects} (set (keys leaf)))
                 (vector? (:subjects leaf))
                 (every? spice-object-shape? (:subjects leaf))
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

(defn completed-answer-value-valid?
  "Validates one completed authorization answer against its semantic key.

  The predicate guards supported cache ingress: completed publication and
  snapshot restore. Exact resident lookup is ordinary membership by a complete
  semantic key, so an already accepted value is not validated again per hit."
  [operation semantic-key value]
  (and (map? semantic-key)
       (= operation (:operation semantic-key))
       (case operation
         :can? (boolean? value)
         :read-relationships (page-answer? value)
         :lookup-resources (page-answer? value)
         :lookup-subjects (page-answer? value)
         :count-resources (count-answer? semantic-key value)
         :count-subjects (count-answer? semantic-key value)
         :expand-permission-tree (permission-tree-answer? value)
         false)))

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
  "Validates and reconstructs fresh LRUs off-side, then installs atomically."
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
       (:lifecycle store) restored % (:content-change-fn store)))
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
  [subproblem-store key]
  (some-> (subproblem/lookup!
           subproblem-store :answer key)
          :value))

(defn- lookup-managed-answer
  [subproblem-store key requested-revision]
  (some-> (subproblem/lookup-eligible!
           subproblem-store :answer key
           #(managed-answer-causally-eligible?
             requested-revision %))
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
    {:valid? answer-entry-valid?}
    entry)))

(defn- cache-hit-result
  [store tier entry]
  (record-metrics!
   store
   (fn [metrics]
     (-> metrics
         (update :hits inc)
         (update (if (= tier :exact-basis)
                   :exact-hits
                   :managed-hits)
                 inc))))
  {:value (:value entry)
   :cached? true
   :cache-tier tier
   :cache-basis (:cache-basis entry)})

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
   {:keys [exact-basis-key cache-lifecycle managed-key-fn populate-cache?]
    :or {populate-cache? true}}
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
          exact-entry (lookup-answer subproblem-store exact-key)]
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
                   subproblem-store managed-key revision))]
            (if managed-entry
              (do
                (when populate-cache?
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
                    (publish-answer! store subproblem-store exact-key entry))
                  {:value value
                   :cached? false
                   :cache-tier nil
                   :cache-basis cache-basis})))))))))

(defn resolve-managed-read-only!
  "Consults only a causally valid managed key; a miss computes without writes."
  [store
   {:keys [cache-lifecycle snapshot-order managed-source managed-key-fn]}
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
                 subproblem-store managed-key snapshot-order))]
          (if entry
            (cache-hit-result store :managed-current entry)
            (uncached-result store compute)))))))
