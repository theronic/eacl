(ns eacl.formal.indexed-bridge-fixture
  "Portable in-memory adapters for comparing the handwritten indexed engine
  with the production v8 engine in both Clojure and ClojureScript."
  (:require [eacl.backend.v8 :as backend]))

(defn apply-window
  [values cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (if (map? cursor-or-options)
          cursor-or-options
          {:direction :asc
           :bound-eid cursor-or-options
           :inclusive-bound? false})
        direction (or direction :asc)
        within?
        (case direction
          :asc (if (some? bound-eid)
                 (if inclusive-bound?
                   #(<= bound-eid %)
                   #(< bound-eid %))
                 (constantly true))
          :desc (if (some? bound-eid)
                  (if inclusive-bound?
                    #(>= bound-eid %)
                    #(> bound-eid %))
                  (constantly true)))]
    (cond->> values
      :always sort
      :always (filter within?)
      (= :desc direction) reverse)))

(defn pure-adapters
  [fixture]
  (let [objects (:objects fixture)
        external->internal
        (into {}
              (map-indexed
               (fn [index object]
                 [object (+ 1000 index)])
               objects))
        id->object
        (into {} (map (juxt :id identity)) objects)
        internal->object
        (into {} (map (fn [[object internal]]
                        [internal object]))
              external->internal)
        relations
        (into {}
              (map-indexed
               (fn [index relation]
                 [[(:resource-type relation)
                   (:relation-name relation)]
                  (assoc relation :relation-id (+ 2000 index))])
               (:relations fixture)))
        permissions
        (->> (:permissions fixture)
             (map-indexed
              (fn [index permission]
                (assoc permission :permission-id (+ 3000 index))))
             (group-by
              (juxt :resource-type :permission-name)))
        relation-defs
        (fn [resource-type relation-name]
          (if-let [definition
                   (get relations [resource-type relation-name])]
            [definition]
            []))
        permission-defs
        (fn [resource-type permission-name]
          (get permissions [resource-type permission-name] []))
        relation-name-for-id
        (fn [relation-id]
          (:relation-name
           (first
            (filter
             #(= relation-id (:relation-id %))
             (vals relations)))))
        scan
        (fn [subject-type subject-id relation-id
             resource-type cursor-or-options]
          (let [subject (get internal->object subject-id)
                relation-name (relation-name-for-id relation-id)]
            (apply-window
             (for [{relationship-subject :subject
                    relationship-relation :relation
                    resource :resource}
                   (:relationships fixture)
                   :when
                   (and (= subject relationship-subject)
                        (= subject-type (:type relationship-subject))
                        (= relation-name relationship-relation)
                        (= resource-type (:type resource)))]
               (get external->internal resource))
             cursor-or-options)))
        reverse-scan
        (fn [resource-type resource-id relation-id
             subject-type cursor-or-options]
          (let [resource (get internal->object resource-id)
                relation-name (relation-name-for-id relation-id)]
            (apply-window
             (for [{subject :subject
                    relationship-relation :relation
                    relationship-resource :resource}
                   (:relationships fixture)
                   :when
                   (and (= resource relationship-resource)
                        (= resource-type (:type relationship-resource))
                        (= relation-name relationship-relation)
                        (= subject-type (:type subject)))]
               (get external->internal subject))
             cursor-or-options)))
        direct-match?
        (fn [subject-type subject-id relation-id
             resource-type resource-id]
          (let [subject (get internal->object subject-id)
                resource (get internal->object resource-id)
                relation-name (relation-name-for-id relation-id)]
            (boolean
             (some
              #(and (= subject (:subject %))
                    (= subject-type (get-in % [:subject :type]))
                    (= relation-name (:relation %))
                    (= resource (:resource %))
                    (= resource-type
                       (get-in % [:resource :type])))
              (:relationships fixture)))))
        legacy
        {:cache-stamp (constantly 1)
         :relation-defs relation-defs
         :permission-defs permission-defs
         :subject->resources scan
         :resource->subjects reverse-scan
         :direct-match? direct-match?}
        operations
        {:snapshot-id (constantly {:source :memory :revision 1})
         :source-scope (constantly {:source-id :memory :branch nil})
         :graph-head
         (constantly
          {:graph-anchor :memory-1
           :order-hint 1
           :exact-locator :memory-1})
         :contains-anchor? #(= :memory-1 %)
         :order-hint (constantly 1)
         :select-current (constantly nil)
         :select-authoritative (fn [_] nil)
         :select-at-least (fn [_ _] nil)
         :exact-locator (constantly :memory-1)
         :select-exact (fn [_ _] nil)
         :object-id->internal
         (fn [object-id]
           (some-> (get id->object object-id)
                   external->internal))
         :internal-id->object
         (fn [internal-id]
           (:id (get internal->object internal-id)))
         :relation-defs relation-defs
         :permission-defs permission-defs
         :subject->resources scan
         :resource->subjects reverse-scan
         :direct-match? direct-match?
         :relation-populated? (fn [& _] false)
         :all-permission-nodes
         (constantly (:permission-nodes fixture))
         :schema-proof (fn ([] :schema-1) ([_] :schema-1))
         :relation-proof
         (fn [relation-ids]
           [:relations (vec (sort relation-ids))])}
        adapter
        (backend/make-adapter
         {:id :formal-memory
          :capabilities {}
          :operations operations})]
    {:v8 adapter
     :indexed legacy
     :external->internal external->internal
     :internal->object internal->object}))
