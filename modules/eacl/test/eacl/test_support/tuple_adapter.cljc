(ns eacl.test-support.tuple-adapter
  "An in-memory v8 adapter over a validated schema and an explicit set of
  relationship tuples, for engine-level tests and mutation controls on both
  runtimes. Object ids are the internal ids."
  (:require [eacl.backend.v8 :as backend]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]))

(defn- inert-operations
  []
  (into {}
        (map (fn [operation]
               [operation (fn [& _] nil)]))
        backend/required-snapshot-operations))

(defn from-validated
  "Builds a v8 adapter over `validated` schema whose relationship tuples are
  exactly `relationships`: a set of
  `[subject-type subject-eid relation-name resource-type resource-eid]`.
  Relation names resolve to the deterministic relation ids the sealed plan
  sees, and both scan directions honor strict eid order and exclusive or
  inclusive bounds."
  [validated relationships]
  (let [candidate (persistence/candidate-schema validated)
        relation-key (juxt :eacl.relation/resource-type
                           :eacl.relation/relation-name
                           :eacl.relation/subject-type)
        rows (->> (:relations candidate)
                  (sort-by relation-key)
                  (map-indexed
                   (fn [index relation]
                     {:relation-id (+ 100 index)
                      :resource-type (:eacl.relation/resource-type relation)
                      :relation-name (:eacl.relation/relation-name relation)
                      :subject-type (:eacl.relation/subject-type relation)}))
                  vec)
        relation-ids (into {}
                           (map (fn [row]
                                  [[(:resource-type row)
                                    (:relation-name row)]
                                   (:relation-id row)]))
                           rows)
        relations (group-by (juxt :resource-type :relation-name) rows)
        expressions (into {}
                          (map (fn [entity]
                                 [[(:eacl.permission/resource-type entity)
                                   (:eacl.permission/permission-name entity)]
                                  entity]))
                          (:permissions candidate))
        tuples (into #{}
                     (map (fn [[subject-type subject-eid relation-name
                                resource-type resource-eid]]
                            [subject-type subject-eid
                             (get relation-ids
                                  [resource-type relation-name])
                             resource-type resource-eid]))
                     relationships)
        scan (fn [match-fn extract-fn]
               (fn [type-a eid-a relation-id type-b
                    {:keys [direction bound-eid inclusive-bound?]}]
                 (let [eids (->> tuples
                                 (filter #(match-fn % type-a eid-a
                                                    relation-id type-b))
                                 (map extract-fn)
                                 sort
                                 vec)
                       eids (if (= :desc direction)
                              (vec (reverse eids))
                              eids)]
                   (cond->> eids
                     (some? bound-eid)
                     (filterv
                      (fn [eid]
                        (if (= :desc direction)
                          (if inclusive-bound?
                            (<= eid bound-eid)
                            (< eid bound-eid))
                          (if inclusive-bound?
                            (>= eid bound-eid)
                            (> eid bound-eid)))))))))]
    (backend/make-adapter
     {:id :operator-mutation-control
      :capabilities backend/empty-capabilities
      :operations
      (merge
       (inert-operations)
       {:snapshot-id (constantly {:snapshot :operator-mutation-control})
        :basis-kind (constantly :ordinary)
        :native-revision (constantly {:revision 1})
        :order-hint (constantly 1)
        :exact-locator (constantly nil)
        :object-id->internal identity
        :internal-id->object identity
        :relation-defs
        (fn [resource-type relation-name]
          (mapv #(select-keys % [:relation-id :resource-type
                                 :relation-name :subject-type])
                (get relations [resource-type relation-name] [])))
        :permission-expression
        (fn [resource-type permission-name]
          (get expressions [resource-type permission-name]))
        :permission-defs
        (fn [resource-type permission-name]
          (when-let [entity (get expressions
                                 [resource-type permission-name])]
            (persistence/union-compatible-definitions
             (:eacl/id entity)
             (persistence/decode-entity entity))))
        :subject->resources
        (scan (fn [[subject-type subject-eid relation resource-type _]
                   type-a eid-a relation-id type-b]
                (and (= subject-type type-a) (= subject-eid eid-a)
                     (= relation relation-id) (= resource-type type-b)))
              (fn [[_ _ _ _ resource-eid]] resource-eid))
        :resource->subjects
        (scan (fn [[subject-type _ relation resource-type resource-eid]
                   type-a eid-a relation-id type-b]
                (and (= resource-type type-a) (= resource-eid eid-a)
                     (= relation relation-id) (= subject-type type-b)))
              (fn [[_ subject-eid _ _ _]] subject-eid))
        :direct-match?
        (fn [subject-type subject-eid relation-eid
             resource-type resource-eid]
          (contains? tuples [subject-type subject-eid relation-eid
                             resource-type resource-eid]))
        :all-permission-nodes (constantly (set (keys expressions)))})})))

(defn from-schema
  [schema-source relationships]
  (from-validated (resolver/validate-schema schema-source) relationships))
