(ns eacl.authorization.qualification
  "One edge qualification seam for a selected immutable request basis.
   This layer resolves data only; traversal remains in the existing engine."
  (:require [eacl.authorization.context :as context]
            [eacl.authorization.data :as data]
            [eacl.authorization.evidence :as evidence]
            [eacl.backend.v8 :as backend]
            [eacl.cache.standard-lru :as lru]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.values :as values]
            [eacl.execution :as execution]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.request.counters :as counters]))

(def maximum-request-entries 100000)
(defrecord Qualification [time context evaluator entity version basis cache memos lookup prepared-context])

(defn request
  "Captures trusted time and exact source/basis identity supplied by request
   orchestration. Entity/version callbacks close over that same native basis.
   Decode retention is optional and always uses complete exact basis identity."
  [{:keys [time context evaluator entity version basis cache lookup prepared-context] :as options}]
  (when-not (and (values/valid-time? time) (or (nil? context) (map? context))
                (or (and (fn? entity) (fn? version) (nil? lookup))
                    (and (fn? lookup) (nil? entity) (nil? version)))
                (map? basis) (seq basis)
                (or (nil? cache) (lru/store? cache))
                (or (nil? prepared-context)
                    (context/prepared? prepared-context)))
    (qualifier/error! :qualification-context))
  (let [prepared (if (and prepared-context
                          (or (not (contains? options :context))
                              (identical? context (context/value prepared-context))))
                   prepared-context
                   (context/prepare (or context {})))]
    (->Qualification time (context/value prepared) evaluator entity version basis cache
                     (delay (volatile! {})) lookup prepared)))

(defn request-from-adapter
  "Uses only the selected immutable adapter's bounded, metered data operation.
   Entity contents and their assertion version arrive in the same read."
  [adapter options]
  (backend/require-capability! adapter :qualification data/capability)
  (request
   (assoc (dissoc options :entity :version)
          :lookup (fn [eid]
                    (execution/check! :qualification-data/before)
                    (counters/add-commands!)
                    (let [result (backend/invoke adapter :qualification-data eid)]
                      (execution/check! :qualification-data/after)
                      result)))))

(defn- memo! [request key build]
  (let [memos (force (:memos request)) current @memos
        result
        (if (contains? current key)
          (get current key)
          (do
            (when (>= (count current) maximum-request-entries) (qualifier/error! :request-qualifier-limit))
            (let [result (try {:value (build)}
                              (catch #?(:clj Exception :cljs :default) error {:error error}))]
              (vswap! memos assoc key result)
              result)))]
    (if-let [error (:error result)] (throw error) (:value result))))

(defn- entity-data [request eid]
  (if-let [lookup (:lookup request)]
    (memo! request [:entity-data eid]
           #(let [result (lookup eid)]
              (when-not (and (map? result) (= #{:entity :version :fact-count} (set (keys result)))
                             (or (nil? (:entity result)) (map? (:entity result)))
                             (or (nil? (:entity result)) (= eid (get-in result [:entity :db/id])))
                             (or (nil? (:version result)) (qualifier/concrete-eid? (:version result)))
                             (integer? (:fact-count result))
                             (<= 0 (:fact-count result) data/maximum-entity-facts))
                (qualifier/error! :qualification-data))
              result))
    {:entity ((:entity request) eid)}))

(defn- named-definition [request eid]
  (memo! request [:caveat eid]
         #(let [entity (:entity (entity-data request eid))]
            {:entity entity :header (definition/decode-header entity)})))

(defn exact-reuse-identity
  "Complete collision-checked semantic scope for same-basis, same-time reuse.
   Cross-time acceptance additionally requires a certified evidence interval."
  [request]
  (memo! request [:reuse-identity]
         #(vector qualifier/format-version (:basis request) (:time request)
                  (context/identity (:prepared-context request))
                  (when-let [engine (:evaluator request)]
                    (select-keys (evaluator/descriptor engine)
                                 [:profile :profile-fingerprint :fingerprint :capability-version])))))

(defn- decoded [request qid]
  (memo! request [:qualifier qid]
    #(let [key [(:basis request) qid qualifier/format-version]
           cached (when-let [cache (:cache request)] (lru/lookup! cache key))]
       (if (:found? cached)
         (:value cached)
         (let [data (entity-data request qid)
               entity (:entity data)
               caveat-id (get entity qualifier/caveat-attribute)
               _ (when (and (some? caveat-id) (not (qualifier/concrete-eid? caveat-id)))
                   (qualifier/error! :qualifier-ref))
               named (when caveat-id (named-definition request caveat-id))
               value (qualifier/decode entity (get-in named [:header :parameters] []))
               version (if (:lookup request) (:version data) ((:version request) qid))
               _ (when (and (some? version) (not (qualifier/concrete-eid? version)))
                   (qualifier/error! :qualifier-version))
               result {:qualifier value :definition named :version version}]
           (when-let [cache (:cache request)] (lru/put-if-absent! cache key result))
           result)))))

(defn- allowed! [request relation-id caveat-id]
  (let [allowed (memo! request [:relation relation-id]
                       #(let [relation (:entity (entity-data request relation-id))]
                          (when-not (and (map? relation) (seq relation))
                            (qualifier/error! :missing-relation))
                          (qualifier/relation-allowance relation)))]
    (when-not (contains? allowed caveat-id) (qualifier/error! :caveat-not-allowed))))

(defn- caveat-evidence [request named bound]
  (let [projected (context/project (:prepared-context request) (get-in named [:header :parameters]))
        result (evaluator/evaluate (:evaluator request) (:entity named) projected bound)]
    (case (:outcome result)
      :true true
      :false false
      :conditional
      (evidence/conditional
       [values/profile-id evaluator/profile-fingerprint (:entity named)
        (get-in named [:header :parameters]) (:residual result)]
       (:missing-fields result))
      :error (evidence/fault :eacl.caveat/evaluation
                             (if (keyword? (:reason result)) (:reason result) :invalid-outcome))
      (evidence/fault :eacl.caveat/evaluation :invalid-outcome))))

(defn qualify
  "Returns evidence for one compact native edge. The ordinary integer branch
   allocates nothing and never dereferences request state. All authoritative
   faults remain faults, including faults on subtracting edges."
  [request relation-id compact-edge]
  (if-not (vector? compact-edge)
    (some? compact-edge)
    (do
      (execution/check! :qualifier-resolution)
      (try
        (when-not (edge/valid? compact-edge) (qualifier/error! :qualifier-ref))
        (let [{:keys [qualifier definition]} (decoded request (edge/qualifier-id compact-edge))
              {:keys [caveat caveat-context valid-until-ms]} qualifier]
          (allowed! request relation-id caveat)
          (if (and valid-until-ms (>= (:time request) valid-until-ms))
            false
            (evidence/with-certificate
             (if caveat (caveat-evidence request definition caveat-context) true)
             valid-until-ms true)))
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
          (let [{:keys [type reason]} (ex-data error)]
            (if (contains? #{:eacl.qualifier/invalid :eacl.caveat/invalid
                             :eacl.caveat/evaluator-unavailable
                             :eacl.authorization/invalid-evidence} type)
              (evidence/fault type (if (keyword? reason) reason :unavailable))
              (throw error))))))))
