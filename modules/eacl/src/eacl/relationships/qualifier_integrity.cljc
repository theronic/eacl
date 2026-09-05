(ns eacl.relationships.qualifier-integrity
  "Offline qualifier diagnostics and portable proof inputs. Never authorizes."
  (:require [eacl.caveats.definition :as definition]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.storage :as storage]))

(defn- identity-of [decoded]
  (mapv decoded [:subject-type :subject-eid :relation-eid :resource-type :resource-eid]))

(defn proof-input
  "Explicit whole-database scan in one caller-owned snapshot. Retains only
   qualified identities and qualifier entities, plus bounded malformed samples.
   Assertion versions are native evidence; exact-only backends make no claim
   of proving past immutability from a current snapshot."
  [native db]
  (let [malformed (volatile! {:count 0 :sample []})
        collect
        (fn [refs direction attribute decode]
          (reduce (fn [refs datom]
                    (let [value (:v datom) decoded (decode (:e datom) value)
                          qid (:qualifier-eid decoded)]
                      (cond
                        (or (nil? decoded) (and (some? qid) (not (qualifier/concrete-eid? qid))))
                        (do (vswap! malformed
                                    (fn [m] (cond-> (update m :count inc)
                                              (< (count (:sample m)) 20)
                                              (update :sample conj {:direction direction :e (:e datom) :v value}))))
                            refs)
                        (nil? qid) refs
                        :else (update-in refs [qid direction] (fnil conj []) (identity-of decoded)))))
                  refs ((:all-rows native) db attribute)))
        refs (-> {} (collect :forward storage/forward-attribute pair/decode-forward)
                 (collect :reverse storage/reverse-attribute pair/decode-reverse))
        qids (reduce (fn [ids attribute]
                       (into ids (map :e) ((:all-rows native) db attribute)))
                     (set (keys refs)) qualifier/attributes)
        identities (into #{} (mapcat #(concat (:forward %) (:reverse %))) (vals refs))
        relations (into {}
                        (for [rid (set (map #(nth % 2) identities))
                              :let [entity ((:entity native) db rid)]]
                          [rid {:generation (get entity (:relation-version-attribute native))
                                :definition (select-keys entity [:eacl.relation/resource-type :eacl.relation/subject-type
                                                                  :eacl.relation/relation-name :eacl.relation/caveats
                                                                  :eacl.relation/allows-unqualified?])}]))
        duplicates
        (into #{}
              (filter (fn [[st s r rt o]]
                        (or (> (count (take 2 ((:rows native) db s storage/forward-attribute [st r rt o nil]))) 1)
                            (> (count (take 2 ((:rows native) db o storage/reverse-attribute [rt r st s nil]))) 1))))
              identities)
        entities (into {} (map (fn [qid]
                                 [qid {:entity ((:entity native) db qid)
                                       :version ((:qualifier-version native) db qid)
                                       :facts ((:facts native) db qid)}])) qids)
        caveats (into {}
                      (for [eid (set (filter qualifier/concrete-eid?
                                             (keep #(get-in % [:entity qualifier/caveat-attribute]) (vals entities))))]
                        [eid ((:entity native) db eid)]))]
    {:format 1 :source {:backend (:backend native) :id ((:source native) db)}
     :schema-generation ((:generation native) db) :cache-scope (:qualifier-cache-scope native)
     :revision ((:revision native) db)
     :references refs :qualifiers entities :caveats caveats :relations relations
     :duplicate-identities duplicates :malformed-halves @malformed}))

(defn- malformed-reason [frame entity]
  (try
    (let [parameters (when-let [caveat (get entity qualifier/caveat-attribute)]
                       (:parameters (definition/decode-entity (get-in frame [:caveats caveat]))))]
      (qualifier/decode entity parameters)
      nil)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (or (:reason (ex-data e)) :malformed-qualifier))))

(defn- valid-relation-proof? [frame entity [subject-type _ relation-id resource-type _]]
  (let [{:keys [generation definition]} (get-in frame [:relations relation-id])]
    (and generation
         (= subject-type (:eacl.relation/subject-type definition))
         (= resource-type (:eacl.relation/resource-type definition))
         (keyword? (:eacl.relation/relation-name definition))
         (try (contains? (qualifier/relation-allowance definition) (get entity qualifier/caveat-attribute))
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))))

(defn report
  "Diagnoses captured proof input. :before adds explicit mutation evidence;
   source identity must match. Orphans are inert and reported separately from
   corruption. Examples and cleanup candidates are bounded by :sample-size."
  ([frame] (report frame {}))
  ([frame {:keys [before sample-size] :or {sample-size 20}}]
   (when-not (and (integer? sample-size) (<= 0 sample-size 1000))
     (throw (ex-info "Invalid qualifier report sample size."
                     {:type :eacl.integrity/invalid-options :eacl/error :eacl.integrity/invalid-options})))
   (when (and before (not= (:source before) (:source frame)))
     (throw (ex-info "Qualifier proof sources differ."
                     {:type :eacl.integrity/source-mismatch :eacl/error :eacl.integrity/source-mismatch})))
   (let [events
         (mapcat
           (fn [[qid {:keys [entity version] :as captured}]]
             (let [{:keys [forward reverse]} (get-in frame [:references qid])
                   owners (set (concat forward reverse))
                   prior (get-in before [:qualifiers qid])
                   reason (when entity (malformed-reason frame entity))
                   mutable? (and (:entity prior) entity
                                 (or (not= (:entity prior) entity)
                                     (and version (:version prior) (not= version (:version prior)))))
                   referenced? (seq owners)]
               (cond-> []
                 (and referenced? (nil? entity)) (conj {:kind :missing-qualifier :qualifier qid})
                 (> (count owners) 1) (conj {:kind :shared-qualifier :qualifier qid})
                 (and referenced? (not= (frequencies forward) (frequencies reverse)))
                 (conj {:kind :asymmetric-qualifier :qualifier qid})
                 reason (conj {:kind :malformed-qualifier :qualifier qid :reason reason})
                 mutable? (conj {:kind :mutable-qualifier :qualifier qid})
                 (and entity (some #(not (valid-relation-proof? frame entity %)) owners))
                 (conj {:kind :invalid-relation-proof :qualifier qid})
                 (and entity (not referenced?)) (conj {:kind :unattached-qualifier :qualifier qid
                                                     :cleanup-eligible? (and (nil? reason) (not mutable?))}))))
           (sort-by key (:qualifiers frame)))
         events (concat events (map #(hash-map :kind :duplicate-relationship-identity :identity %)
                                    (sort (:duplicate-identities frame))))
         summary
         (reduce (fn [summary event]
                   (cond-> (update-in summary [:counts (:kind event)] (fnil inc 0))
                     (< (count (:sample summary)) sample-size) (update :sample conj event)
                     (and (:cleanup-eligible? event) (< (count (:cleanup-candidates summary)) sample-size))
                     (update :cleanup-candidates conj (:qualifier event))))
                 {:counts {} :sample [] :cleanup-candidates []} events)
         counts (cond-> (:counts summary)
                  (pos? (get-in frame [:malformed-halves :count] 0))
                  (assoc :malformed-relationship-half (get-in frame [:malformed-halves :count])))
         errors (reduce + 0 (vals (dissoc counts :unattached-qualifier)))]
     (assoc summary :counts counts :status (if (zero? errors) :healthy :corrupt) :error-count errors
            :qualifier-count (count (:qualifiers frame)) :source (:source frame)
            :immutability-evidence (if before :before-and-after :snapshot-only)
            :malformed-half-sample (vec (take sample-size (get-in frame [:malformed-halves :sample])))
            :cache-scope (:cache-scope frame)))))

(defn cleanup-plan
  "Plans a bounded orphan batch behind an exact native head guard. The guard
   prevents a qualifier from being attached anywhere after the offline scan.
   No owner sidecar or global qualifier generation is required."
  [native db {:keys [batch-size] :or {batch-size 100}}]
  (when-not (and (integer? batch-size) (<= 1 batch-size 1000))
    (throw (ex-info "Invalid orphan cleanup batch size."
                    {:type :eacl.integrity/invalid-options :eacl/error :eacl.integrity/invalid-options})))
  (let [frame (proof-input native db)
        report (report frame {:sample-size batch-size})
        qids (:cleanup-candidates report)]
    (when-not (= :healthy (:status report))
      (throw (ex-info "Repair corrupt qualifier data before collecting orphans."
                      {:type :eacl.integrity/corrupt-qualifiers :eacl/error :eacl.integrity/corrupt-qualifiers
                       :counts (:counts report)})))
    {:qualifiers qids :source (:source frame) :revision (:revision frame)
     :tx-data (when (seq qids)
                (into [((:head-guard native) db)]
                      (mapcat (fn [qid]
                                [((:assert-entity native) qid (get-in frame [:qualifiers qid :facts]))
                                 [:db/retractEntity qid]])) qids))}))

(defn cleanup-orphans!
  "Collects one bounded batch using an admitted staged writer. Suitable after
   process restart when preparation handles are no longer available."
  ([writer] (cleanup-orphans! writer {}))
  ([writer options]
   (let [native (:native writer)
         plan ((:with-snapshot native)
               (fn [db]
                 (when-not (= (:source writer) ((:source native) db))
                   (throw (ex-info "Qualifier cleanup source changed."
                                   {:type :eacl.integrity/source-mismatch :eacl/error :eacl.integrity/source-mismatch})))
                 (cleanup-plan native db options)))]
     (when (seq (:tx-data plan)) ((:transact! native) (:tx-data plan)))
     (dissoc plan :tx-data))))
