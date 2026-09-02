(ns eacl.schema.model
  (:require [clojure.set :as set]
            [clojure.string :as str]))

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
                    {:type :eacl.schema/invalid-permission-spec
                     :eacl/error :eacl.schema/invalid-permission-spec
                     :spec spec}))))

(defn validate-schema-references
  "Validates that all permission references are valid and, when the schema
   carries its `:definitions` (as `eacl.spicedb.parser/->eacl-schema` output
   does), that every relation subject type names a defined definition.
   Returns nil if valid, throws a typed ex-info
   (`:eacl.schema/invalid-reference`) with :errors vector if invalid."
  [{:keys [relations permissions definitions]}]
  (let [relations-by-type        (group-by :eacl.relation/resource-type relations)
        permissions-by-type      (group-by :eacl.permission/resource-type permissions)
        relation-names-by-type   (into {}
                                       (for [[rt rels] relations-by-type]
                                         [rt (set (map :eacl.relation/relation-name rels))]))
        permission-names-by-type (into {}
                                       (for [[rt perms] permissions-by-type]
                                         [rt (set (map :eacl.permission/permission-name perms))]))
        ;; Subject types per relation as full SETS: multi-type relations
        ;; (relation owner: user | group) expand to one entry per type, and
        ;; validation must be independent of declaration order.
        relation-subject-types   (reduce (fn [acc rel]
                                           (update acc
                                                   [(:eacl.relation/resource-type rel)
                                                    (:eacl.relation/relation-name rel)]
                                                   (fnil conj #{})
                                                   (:eacl.relation/subject-type rel)))
                                         {}
                                         relations)
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
              (doseq [target-res-type (get relation-subject-types [res-type source-rel])]
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
                                              " - permission '" (name target-name) "' does not exist on " (name target-res-type))})))))))))
    ;; Relation subject types must be defined definitions (SpiceDB rejects
    ;; `relation reader: nobody`). Only enforceable when the schema carries
    ;; its definition list; data-installed schemas that pass relations and
    ;; permissions alone are validated for references only.
    (when (seq definitions)
      (let [defined (into #{} (map keyword) definitions)]
        (doseq [rel relations
                :let [subject-type (:eacl.relation/subject-type rel)]
                :when (not (contains? defined subject-type))]
          (swap! errors conj
                 {:type          :undefined-subject-type
                  :resource-type (:eacl.relation/resource-type rel)
                  :relation      (:eacl.relation/relation-name rel)
                  :subject-type  subject-type
                  :message       (str "Relation " (name (:eacl.relation/resource-type rel)) "/"
                                      (name (:eacl.relation/relation-name rel))
                                      " - subject type '" (name subject-type)
                                      "' is not a defined definition")}))))
    (when (seq @errors)
      (let [messages (->> @errors
                          (map :message)
                          distinct
                          vec)
            summary  (str "Invalid schema: reference validation failed. "
                          (str/join "; " messages))]
        (throw (ex-info summary
                        {:type        :eacl.schema/invalid-reference
                         :eacl/error  :eacl.schema/invalid-reference
                         :errors      @errors
                         :messages    messages
                         :error-count (count @errors)}))))
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
