(ns eacl.datomic.impl-indexed
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.schema :as schema]))

(def ->user (partial spice-object :user))

(defn entity->spice-object [eacl-ent]
  (spice-object (get eacl-ent :eacl/type) (get eacl-ent :eacl/id)))

(defn lazy-direct-permission-resources
  "Lazily fetches resource EIDs where subject has direct permission."
  [db subject-eid relation resource-type cursor-eid]
  ;(log/debug "lazy-direct-permission-resources" {:subject-eid subject-eid, :relation relation, :resource-type resource-type, :cursor-eid cursor-eid})
  (let [;; Use the subject+relation-name index
        tuple-val [subject-eid relation resource-type nil]
        datoms    (d/index-range db :eacl.relationship/subject+relation-name+resource-type+resource tuple-val nil)]
    ;(log/debug 'datoms (into [] datoms))
    (->> datoms
         ;; Get the relationship entities and their resources
         (take-while (fn [{:as datom, v :v}]
                       (let [[datom-subject datom-relation datom-rtype _] v]
                         (and (= datom-subject subject-eid)
                              (= datom-relation relation)
                              (= datom-rtype resource-type)))))
         (map (fn [{:as datom, v :v}]
                (let [[_ _ resource-type resource-eid] v]
                  ;(log/debug 'found-resource resource-type resource-eid)
                  resource-eid)))
         ;; Apply cursor filtering if needed
         (drop-while (fn [resource-eid]
                       (and cursor-eid (not= resource-eid cursor-eid))))
         ;; Skip the cursor itself if present
         (drop (if cursor-eid 1 0)))))

(defn get-permission-paths
  [db resource-type permission]
  (let [direct-relations (d/q '[:find [?relation-name ...]
                                :in $ ?resource-type ?permission
                                :where
                                [(tuple ?resource-type ?permission) ?rtype+perm]
                                [?perm :eacl.permission/resource-type+permission-name ?rtype+perm]
                                [?perm :eacl.permission/relation-name ?relation-name]]
                              db resource-type permission)
        arrows           (d/q '[:find ?source-relation ?target-permission
                                :in $ ?resource-type ?permission
                                :where
                                [(tuple ?resource-type ?permission) ?rtype+perm]
                                [?arrow :eacl.arrow-permission/resource-type+permission-name ?rtype+perm]
                                [?arrow :eacl.arrow-permission/source-relation-name ?source-relation]
                                [?arrow :eacl.arrow-permission/target-permission-name ?target-permission]]
                              db resource-type permission)]
    (concat
      (map (fn [relation] [[] relation]) direct-relations)
      (for [[source-relation target-permission] arrows
            :let [target-resource-type (d/q '[:find ?subject-type .
                                              :in $ ?resource-type ?source-relation
                                              :where
                                              [?relation :eacl.relation/resource-type ?resource-type]
                                              [?relation :eacl.relation/relation-name ?source-relation]
                                              [?relation :eacl.relation/subject-type ?subject-type]]
                                            db resource-type source-relation)]
            :when target-resource-type
            :let [sub-paths (get-permission-paths db target-resource-type target-permission)]
            [sub-steps sub-direct-relation] sub-paths]
        [(cons {:relation             source-relation
                :source-resource-type resource-type
                :target-resource-type target-resource-type}
               sub-steps)
         sub-direct-relation]))))

(defn lazy-arrow-permission-resources
  "Lazily fetches resource EIDs via arrow permissions."
  [db subject-eid steps direct-relation resource-type cursor-eid]
  ;(log/debug "lazy-arrow-permission-resources" {:subject-eid subject-eid, :steps steps, :direct-relation direct-relation, :cursor-eid cursor-eid})
  (let [;; For arrow permissions like server.account->admin, we need to:
        ;; 1. Find entities where subject has the direct-relation permission        ;; 2. For each of those entities, follow the arrow relation chain back to the target resource type                ;; Start by finding entities where subject has the terminal permission
        initial-step  (last steps)
        initial-type  (:target-resource-type initial-step)
        initial-eids  (lazy-direct-permission-resources db subject-eid direct-relation initial-type cursor-eid)
        ;_             (log/debug "initial eids for type" initial-type ":" (count (into [] initial-eids)))

        ;; Now traverse backwards through the steps
        reverse-steps (reverse steps)]
    (reduce
      (fn [current-eids step]                               ; we'll need to track offsets here unfortunately and switch to reduce. also need to deduplicate IDs.
        ;(log/debug "arrow step:" {:step step, :current-eids (count (into [] current-eids))})
        ;; For each current entity, find entities that it points to with the given relation
        (let [next-eids                                     ;doall                               ; do we need doall here?
              (mapcat
                (fn [eid]
                  ;; Find relationships where this eid is the subject with the given relation
                  (let [relation             (:relation step)
                        source-resource-type (:source-resource-type step)
                        start-tuple          [eid relation source-resource-type nil]
                        datoms               (d/index-range db :eacl.relationship/subject+relation-name+resource-type+resource start-tuple nil)
                        matches              (->> datoms
                                                  (take-while (fn [{:as datom, v :v}]
                                                                (let [[datom-subject datom-relation datom-rtype datom-resource] v]
                                                                  (and (= datom-subject eid)
                                                                       (= datom-relation relation)
                                                                       (= datom-rtype source-resource-type)))))
                                                  (map (fn [{:as datom, v :v}]
                                                         (let [[datom-subject datom-relation datomic-rtype datom-resource] v]
                                                           datom-resource)))
                                                  ; TODO: suspect drop-while has to be before take-while...
                                                  ; I'm not sure this is needed
                                                  (drop-while (fn [resource-eid] ; TODO more complex cursor needed here to avoid dupes.
                                                                (and cursor-eid (not= resource-eid cursor-eid)))))]
                    ; watch out: this materializes everything.
                    ;(log/debug "For eid" eid "found" (count (into [] matches)) "entities it points to via" (:relation step))
                    matches))
                current-eids)]
          ;(log/debug "next eids:" (count next-eids))
          next-eids))
      initial-eids
      reverse-steps)))

(defn lookup-resources
  "Lazily finds resources where subject has permission."
  [db {:as           _query
       subject       :subject
       permission    :permission
       resource-type :resource/type
       cursor        :cursor
       limit         :limit
       :or           {cursor nil
                      limit  1000}}]
  {:pre [(:type subject)
         (keyword? (:type subject))
         (:id subject)
         (keyword? permission)
         (keyword? resource-type)]}
  (let [subject-eid              (d/entid db [:eacl/id (:id subject)])
        paths                    (get-permission-paths db resource-type permission)
        ;_                        (log/debug "lookup-resources paths" paths)
        ;; Handle cursor as either a string or a cursor object
        cursor-path-idx          (if (string? cursor) 0 (or (:path-index cursor) 0))
        cursor-resource-id       (if (string? cursor) cursor (:resource-id cursor))
        cursor-eid               (when cursor-resource-id (d/entid db [:eacl/id cursor-resource-id]))
        ;_                        (log/debug 'cursor-eid cursor-eid)

        ;; Create a lazy sequence of all resource eids with their path indices
        resource-eids-with-paths (->> paths
                                      (map-indexed vector)
                                      (mapcat (fn [[path-idx [steps direct-relation]]]
                                                (let [eids (if (empty? steps)
                                                             (lazy-direct-permission-resources db subject-eid direct-relation resource-type nil)
                                                             (lazy-arrow-permission-resources db subject-eid steps direct-relation resource-type nil))]
                                                  (map #(vector % path-idx) eids)))))

        ;; Apply deduplication and cursor filtering lazily
        ;; can we ditch the paths at this point?

        seen                     (volatile! #{})            ; TODO: optimize. seen set can be passed in earlier to lazy-*.
        deduplicated-resources   (filter (fn [[eid path-idx]]
                                           (if (contains? @seen eid)
                                             false
                                             (do (vswap! seen conj eid)
                                                 true)))
                                         resource-eids-with-paths)

        ; we shouldn't need to do this. It's possible we return less than limit because of dupes.
        ;; Apply cursor filtering if needed
        ;; this seems superfluous.
        resources-after-cursor   (if cursor-eid
                                   (drop-while (fn [[eid _]]
                                                 (not= eid cursor-eid))
                                               deduplicated-resources)
                                   deduplicated-resources)

        ;; Skip the cursor itself if present
        resources-to-return      (if (and cursor-eid (seq resources-after-cursor))
                                   (rest resources-after-cursor)
                                   resources-after-cursor)

        ;; Apply pagination - only materialize what we need
        paginated-resources      (take limit resources-to-return)
        realized-resources       (doall paginated-resources)

        ;; Create cursor for next page
        [last-eid last-path-idx] (last realized-resources)
        new-cursor               (when last-eid
                                   {:path-index  last-path-idx
                                    :resource-id (:eacl/id (d/entity db last-eid))})

        ;; Convert to spice objects
        resources                (map (fn [[eid _]] (entity->spice-object (d/entity db eid))) realized-resources)]
    {:cursor new-cursor
     :data   resources}))

(defn count-resources
  ; this could reuse most of lookup-resources. unfortunately can't get around deduplication.
  "Counts resources where subject has permission. Materializes full index, so can be slow!"
  [db {:as           _query
       subject       :subject
       permission    :permission
       resource-type :resource/type
       cursor        :cursor                                ; only count after-cursor.
       :or           {cursor nil}}]
  {:pre [(:type subject)
         (keyword? (:type subject))
         (:id subject)
         (keyword? permission)
         (keyword? resource-type)]}
  (let [subject-eid              (d/entid db [:eacl/id (:id subject)])
        paths                    (get-permission-paths db resource-type permission)
        ;_                        (log/debug "lookup-resources paths" paths)
        ;; Handle cursor as either a string or a cursor object
        cursor-path-idx          (if (string? cursor) 0 (or (:path-index cursor) 0))
        cursor-resource-id       (if (string? cursor) cursor (:resource-id cursor))
        cursor-eid               (when cursor-resource-id (d/entid db [:eacl/id cursor-resource-id]))
        ;_                        (log/debug 'cursor-eid cursor-eid)

        ;; Create a lazy sequence of all resource eids with their path indices
        resource-eids-with-paths (->> paths
                                      (map-indexed vector)
                                      (mapcat (fn [[path-idx [steps direct-relation]]]
                                                (let [eids (if (empty? steps)
                                                             (lazy-direct-permission-resources db subject-eid direct-relation resource-type nil)
                                                             (lazy-arrow-permission-resources db subject-eid steps direct-relation resource-type nil))]
                                                  (map #(vector % path-idx) eids)))))

        ;; Apply deduplication and cursor filtering lazily
        ;; deduplication costs O(N) unfortunately
        seen                     (volatile! #{})            ; can be optimized. a seen set can be passed in earlier.
        deduplicated-resources   (filter (fn [[eid path-idx]]
                                           (if (@seen eid)
                                             false
                                             (do (vswap! seen conj eid)
                                                 true)))
                                         resource-eids-with-paths)

        resources-after-cursor   (if cursor-eid
                                   (drop-while (fn [[eid _]]
                                                 (not= eid cursor-eid))
                                               deduplicated-resources)
                                   deduplicated-resources)

        ;; Skip the cursor itself if present
        resources-to-return      (if (and cursor-eid (seq resources-after-cursor))
                                   (rest resources-after-cursor)
                                   resources-after-cursor)

        total-count              (count resources-to-return)]
    ;; Convert to spice objects
    total-count))

(comment
  ;; Setup test database
  (def uri "datomic:mem://claude-eacl-test")
  (d/create-database uri)
  (d/delete-database uri)
  (def conn (d/connect uri))
  (.release conn)

  @(d/transact conn schema/v4-schema)
  @(d/transact conn fixtures/base-fixtures)

  (get-permission-paths (d/db conn) :server :view)
  (get-permission-paths (d/db conn) :server :reboot)
  (get-permission-paths (d/db conn) :account :view)

  ; user1-eid (:db/id (d/entity db [:eacl/id "user-1"]))

  (let [db             (d/db conn)
        super-user-eid (d/entid db [:eacl/id "super-user"])
        paths          (get-permission-paths db :server :view)]
    (prn 'super-user-eid super-user-eid)
    (prn 'paths paths)
    (doall
      (for [[steps direct-relation] paths
            :when (seq steps)]
        (do (log/debug 'steps steps 'direct-relation direct-relation)
            (->> (lazy-arrow-permission-resources db super-user-eid steps direct-relation :account nil)
                 (map #(d/entity (d/db conn) %))
                 (map d/touch))))))

  (time (lookup-resources (d/db conn)
                          {:subject       (->user "super-user")
                           :permission    :view
                           :resource/type :server
                           :cursor        nil
                           :limit         1000}))

  (lookup-resources (d/db conn)
                    {:subject       (->user "user-1")
                     :permission    :view
                     :resource/type :server
                     :cursor        nil
                     :limit         1000})

  (lookup-resources (d/db conn)
                    {:subject       (->user "account-1")
                     :permission    :view
                     :resource/type :server
                     :cursor        nil
                     :limit         10})

  (lookup-resources (d/db conn) {:subject       (->user "account-1")
                                 :permission    :view
                                 :resource/type :server
                                 :cursor        "account1-server1"
                                 :limit         10})

  (time (lookup-resources (d/db conn) {:subject       (->user "user-2")
                                       :permission    :view
                                       :resource/type :server
                                       :cursor        nil
                                       :limit         1000}))

  (time (lookup-resources (d/db conn)
                          {:subject       (->user "user-2")
                           :permission    :view
                           :resource/type :server
                           :cursor        nil
                           :limit         1000}))

  ; Actual Data in Fixtures DB:

  ; Relations:
  (d/q '[:find ?resource-type ?relation ?subject-type
         :in $
         :where
         [?rel :eacl.relation/resource-type ?resource-type]
         [?rel :eacl.relation/relation-name ?relation]
         [?rel :eacl.relation/subject-type ?subject-type]]
       (d/db conn))
  ;=>
  #{[:server :owner :user]
    [:platform :super_admin :user]
    [:team :account :account]
    [:account :owner :user]
    [:server :account :account]
    [:vpc :account :account]
    [:account :platform :platform]
    [:vpc :owner :user]}

  ; Direct Permissions:
  (d/q '[:find ?resource-type ?relation ?permission
         :in $
         :where
         [?rel :eacl.permission/resource-type ?resource-type]
         [?rel :eacl.permission/relation-name ?relation]
         [?rel :eacl.permission/permission-name ?permission]]
       (d/db conn))
  ; =>
  #{[:server :shared_admin :view]
    [:vpc :owner :admin]
    [:server :owner :view]
    [:account :owner :admin]
    [:server :account :view]
    [:vpc :shared_admin :admin]
    [:platform :super_admin :platform_admin]
    [:server :account :edit]
    [:server :shared_admin :delete]
    [:server :owner :edit]
    [:server :shared_admin :admin]
    [:account :owner :view]
    [:server :owner :delete]}

  ; Arrow Permissions:
  (d/q '[:find ?resource-type ?source-relation ?relation-permission ?permission-to-grant
         :in $
         :where
         [?rel :eacl.arrow-permission/resource-type ?resource-type]
         [?rel :eacl.arrow-permission/source-relation-name ?source-relation]
         [?rel :eacl.arrow-permission/target-permission-name ?relation-permission]
         [?rel :eacl.arrow-permission/permission-name ?permission-to-grant]]
       (d/db conn))
  ;=>
  #{[:server :account :admin :reboot]
    [:server :account :admin :delete]
    [:server :account :admin :view]
    [:vpc :account :admin :admin]
    [:account :platform :platform_admin :view]
    [:account :platform :platform_admin :admin]}

  ; Relationships:
  (d/q '[:find ?subject-oid ?relation ?resource-oid
         :in $
         :where
         [?rel :eacl.relationship/subject ?subject-eid]
         [?rel :eacl.relationship/relation-name ?relation]
         [?rel :eacl.relationship/resource ?resource-eid]
         [?subject-eid :eacl/id ?subject-oid]
         [?resource-eid :eacl/id ?resource-oid]]
       (d/db conn))
  ;=>
  #{["user-1" :member "team-1"]
    ["account-1" :account "vpc-1"]
    ["account-2" :account "team-2"]
    ["account-2" :account "vpc-2"]
    ["platform" :platform "account-1"]
    ["super-user" :super_admin "platform"]
    ["platform" :platform "account-2"]
    ["account-1" :account "team-1"]
    ["user-1" :owner "account-1"]
    ["account-1" :account "account1-server1"]
    ["user-2" :owner "account-2"]
    ["account-2" :account "account2-server1"]}

  #_[])

