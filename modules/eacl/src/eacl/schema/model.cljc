(ns eacl.schema.model
  (:require [clojure.set :as set]))

(defn ->relation-id
  "Uses (str kw) instead of (name kw) to retain namespaces. Leading colons are expected."
  [resource-type relation-name subject-type]
  (str "eacl.relation:" resource-type ":" relation-name ":" subject-type))

(defn Relation
  "Defines a logical EACL relation."
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type)
          (keyword? relation-name)
          (keyword? subject-type)
          (not= resource-type :self)
          (not= relation-name :self)]}
   {:eacl/id                     (->relation-id resource-type relation-name subject-type)
    :eacl.relation/resource-type resource-type
    :eacl.relation/relation-name relation-name
    :eacl.relation/subject-type  subject-type})
  ([resource-type+relation-name subject-type]
   {:pre [(keyword? resource-type+relation-name)
          (namespace resource-type+relation-name)
          (keyword? subject-type)]}
   (Relation (keyword (namespace resource-type+relation-name))
             (keyword (name resource-type+relation-name))
             subject-type)))

(defn ->permission-id
  "Uses (str kw) instead of (name kw) to retain namespaces. Leading colons are expected."
  [resource-type permission-name arrow target-type relation-or-permission]
  (str "eacl:permission:" resource-type ":" permission-name ":" arrow ":" target-type ":" relation-or-permission))

(defn Permission
  "Defines a logical EACL permission."
  [resource-type permission-name
   {:as spec
    :keys [arrow relation permission]
    :or {arrow :self}}]
  {:pre [(keyword? resource-type)
         (keyword? permission-name)
         (map? spec)
         (or relation permission)
         (not (and relation permission))]}
  (cond
    relation
    {:eacl/id                              (->permission-id resource-type permission-name arrow :relation relation)
     :eacl.permission/resource-type        resource-type
     :eacl.permission/permission-name      permission-name
     :eacl.permission/source-relation-name arrow
     :eacl.permission/target-type          :relation
     :eacl.permission/target-name          relation}

    permission
    {:eacl/id                              (->permission-id resource-type permission-name arrow :permission permission)
     :eacl.permission/resource-type        resource-type
     :eacl.permission/permission-name      permission-name
     :eacl.permission/source-relation-name arrow
     :eacl.permission/target-type          :permission
     :eacl.permission/target-name          permission}

    :else
    (throw (ex-info "Invalid Permission spec. Expected one of {:relation name}, {:permission name}, {:arrow source :permission target} or {:arrow source :relation target}"
                    {:spec spec}))))

(defn validate-schema-references
  "Validates that all permission references are valid.
   Returns nil if valid, throws ex-info with :errors vector if invalid."
  [{:keys [relations permissions]}]
  (let [relations-by-type        (group-by :eacl.relation/resource-type relations)
        permissions-by-type      (group-by :eacl.permission/resource-type permissions)
        relation-names-by-type   (into {}
                                       (for [[rt rels] relations-by-type]
                                         [rt (set (map :eacl.relation/relation-name rels))]))
        permission-names-by-type (into {}
                                       (for [[rt perms] permissions-by-type]
                                         [rt (set (map :eacl.permission/permission-name perms))]))
        relation-subject-types   (into {}
                                       (for [rel relations]
                                         [[(:eacl.relation/resource-type rel)
                                           (:eacl.relation/relation-name rel)]
                                          (:eacl.relation/subject-type rel)]))
        errors                   (atom [])]
    (doseq [perm permissions]
      (let [res-type    (:eacl.permission/resource-type perm)
            perm-name   (:eacl.permission/permission-name perm)
            source-rel  (:eacl.permission/source-relation-name perm)
            target-type (:eacl.permission/target-type perm)
            target-name (:eacl.permission/target-name perm)]
        (if (= source-rel :self)
          (if (= target-type :relation)
            (when-not (contains? (get relation-names-by-type res-type) target-name)
              (swap! errors conj
                     {:type       :invalid-self-relation
                      :permission (str (name res-type) "/" (name perm-name))
                      :target     target-name
                      :message    (str "Permission " (name res-type) "/" (name perm-name)
                                       " references non-existent relation: " (name target-name))}))
            (when-not (contains? (get permission-names-by-type res-type) target-name)
              (swap! errors conj
                     {:type       :invalid-self-permission
                      :permission (str (name res-type) "/" (name perm-name))
                      :target     target-name
                      :message    (str "Permission " (name res-type) "/" (name perm-name)
                                       " references non-existent permission: " (name target-name))})))
          (do
            (when-not (contains? (get relation-names-by-type res-type) source-rel)
              (swap! errors conj
                     {:type       :missing-source-relation
                      :permission (str (name res-type) "/" (name perm-name))
                      :relation   source-rel
                      :message    (str "Permission " (name res-type) "/" (name perm-name)
                                       " references non-existent relation: " (name source-rel))}))
            (when (contains? (get relation-names-by-type res-type) source-rel)
              (let [target-res-type (get relation-subject-types [res-type source-rel])]
                (when target-res-type
                  (if (= target-type :relation)
                    (when-not (contains? (get relation-names-by-type target-res-type) target-name)
                      (swap! errors conj
                             {:type        :invalid-arrow-target-relation
                              :permission  (str (name res-type) "/" (name perm-name))
                              :arrow-via   source-rel
                              :target-type target-res-type
                              :target      target-name
                              :message     (str "Permission " (name res-type) "/" (name perm-name)
                                                " arrow via " (name source-rel) "->" (name target-name)
                                                " - relation '" (name target-name) "' does not exist on " (name target-res-type))}))
                    (when-not (contains? (get permission-names-by-type target-res-type) target-name)
                      (swap! errors conj
                             {:type        :invalid-arrow-target-permission
                              :permission  (str (name res-type) "/" (name perm-name))
                              :arrow-via   source-rel
                              :target-type target-res-type
                              :target      target-name
                              :message     (str "Permission " (name res-type) "/" (name perm-name)
                                                " arrow via " (name source-rel) "->" (name target-name)
                                                " - permission '" (name target-name) "' does not exist on " (name target-res-type))}))))))))))
    (when (seq @errors)
      (throw (ex-info "Invalid schema: reference validation failed"
                      {:errors      @errors
                       :error-count (count @errors)})))
    nil))

(defn calc-set-deltas [before after]
  {:additions   (set/difference after before)
   :unchanged   (set/intersection before after)
   :retractions (set/difference before after)})

(defn compare-schema
  "Compares before & after schema (without DB IDs) and returns set deltas."
  [{before-relations :relations
    before-permissions :permissions}
   {after-relations :relations
    after-permissions :permissions}]
  {:relations   (calc-set-deltas (set before-relations) (set after-relations))
   :permissions (calc-set-deltas (set before-permissions) (set after-permissions))})
