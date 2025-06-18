(ns eacl.datomic.rules.optimized-old
  "Optimized Datalog rules for EACL performance improvements")

(def check-permission-rules
  "Optimized rules for can? - reordered clauses and better tuple usage"
  '[;; Optimized reachability using tuples
    [(reachable ?resource ?subject)
     ;; Direct relationship - most common case
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]]

    [(reachable ?resource ?subject)
     ;; Indirect relationship - use tuple for first hop
     [(tuple ?resource ?mid) ?resource+mid]
     [?relationship :eacl.relationship/resource+subject ?resource+mid]
     ;; Only traverse if needed
     (reachable ?mid ?subject)]

    ;; Direct permission - optimized clause ordering
    [(has-permission ?subject ?permission-name ?resource)
     ;; Get resource type first
     [?resource :resource/type ?resource-type]

     ;; Find relationships for this resource
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?subject]
     [?relationship :eacl.relationship/relation-name ?relation-name]

     ;; Check permission using tuple
     [(tuple ?resource-type ?relation-name ?permission-name) ?perm-tuple]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]

     ;; Exclude self-references
     [(not= ?subject ?resource)]]

    ;; Indirect permission inheritance - optimized
    [(has-permission ?subject ?permission-name ?resource)
     ;; Get resource type first (we already have the resource)
     [?resource :resource/type ?resource-type]

     ;; Find permission definitions for this resource type
     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name]

     ;; Find structural relationships where resource is the subject
     [?structural-rel :eacl.relationship/subject ?resource]
     [?structural-rel :eacl.relationship/relation-name ?relation-name]
     [?structural-rel :eacl.relationship/resource ?target]

     ;; Check reachability last
     (reachable ?target ?subject)
     [(not= ?subject ?resource)]]

    ;; Arrow permission - optimized
    [(has-permission ?subject ?perm-name-on-this-resource ?this-resource)
     ;; Get resource type from the resource we already have
     [?this-resource :resource/type ?this-resource-type]

     ;; Find arrow permission definitions
     [?arrow-perm :eacl.arrow-permission/resource-type ?this-resource-type]
     [?arrow-perm :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm :eacl.arrow-permission/source-relation-name ?via-relation]
     [?arrow-perm :eacl.arrow-permission/target-permission-name ?perm-on-related]

     ;; Find intermediate resource
     [?rel-linking :eacl.relationship/resource ?this-resource]
     [?rel-linking :eacl.relationship/relation-name ?via-relation]
     [?rel-linking :eacl.relationship/subject ?intermediate-resource]

     ;; Recursive permission check
     (has-permission ?subject ?perm-on-related ?intermediate-resource)

     ;; Safety checks
     [(not= ?subject ?this-resource)]
     [(not= ?subject ?intermediate-resource)]
     [(not= ?this-resource ?intermediate-resource)]]])

(def rules-lookup-subjects
  "Optimized rules for lookup-subjects"
  '[;; Reachability rules remain the same
    [(reachable ?resource ?subject)
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]]

    [(reachable ?resource ?subject)
     [(tuple ?resource ?mid) ?resource+mid]
     [?relationship :eacl.relationship/resource+subject ?resource+mid]
     (reachable ?mid ?subject)]

    ;; Direct permission check - optimized for known resource
    [(has-permission ?subject-type ?subject ?permission-name ?resource)
     ;; Get resource type (we already have resource entity)
     [?resource :resource/type ?resource-type]

     ;; Find relationships for this resource
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?subject]
     [?relationship :eacl.relationship/relation-name ?relation-name]

     ;; Check subject type
     [?subject :resource/type ?subject-type]

     ;; Check permission using tuple
     [(tuple ?resource-type ?relation-name ?permission-name) ?perm-tuple]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]

     [(not= ?subject ?resource)]]

    ;; Indirect permission inheritance
    [(has-permission ?subject-type ?subject ?permission-name ?resource)
     [?resource :resource/type ?resource-type]

     ;; Find permission definitions
     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name]

     ;; Find structural relationships
     [?structural-rel :eacl.relationship/subject ?resource]
     [?structural-rel :eacl.relationship/relation-name ?relation-name]
     [?structural-rel :eacl.relationship/resource ?target]

     (reachable ?target ?subject)
     [?subject :resource/type ?subject-type]
     [(not= ?subject ?resource)]]

    ;; Arrow permission
    [(has-permission ?subject-type ?subject ?perm-name-on-this-resource ?this-resource)
     [?this-resource :resource/type ?this-resource-type]

     ;; Find arrow permissions
     [?arrow-perm :eacl.arrow-permission/resource-type ?this-resource-type]
     [?arrow-perm :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm :eacl.arrow-permission/source-relation-name ?via-relation]
     [?arrow-perm :eacl.arrow-permission/target-permission-name ?perm-on-related]

     ;; Find intermediate
     [?rel-linking :eacl.relationship/resource ?this-resource]
     [?rel-linking :eacl.relationship/relation-name ?via-relation]
     [?rel-linking :eacl.relationship/subject ?intermediate-resource]

     (has-permission ?subject-type ?subject ?perm-on-related ?intermediate-resource)

     [(not= ?subject ?this-resource)]
     [(not= ?subject ?intermediate-resource)]
     [(not= ?this-resource ?intermediate-resource)]]])

(def rules-lookup-resources
  "Optimized rules for lookup-resources - subject-centric approach"
  '[;; Helper rule: find relationships from subject
    [(subject-has-relationships ?subject ?relation ?resource)
     ;; Start from subject

     ;[(tuple ?subject ?relation ?resource) ?subject+relation+resource]
     ;[?relationship :eacl.relationship/]

     [?relationship :eacl.relationship/subject ?subject]
     [?relationship :eacl.relationship/relation-name ?relation]
     [?relationship :eacl.relationship/resource ?resource]]

    ;; Direct permission check - subject-centric
    [(has-permission ?subject ?permission ?resource-type ?resource)
     ;; Start from subject's relationships
     (subject-has-relationships ?subject ?relation ?resource)

     ;; Check if resource is of correct type
     [?resource :resource/type ?resource-type]

     ;; Check if relation grants permission
     [?perm :eacl.permission/resource-type ?resource-type]
     [?perm :eacl.permission/relation-name ?relation]
     [?perm :eacl.permission/permission-name ?permission]]

    ;; Indirect permission via intermediate resources
    [(has-permission ?subject ?permission ?resource-type ?resource)
     ;; Find relationships where subject can reach an intermediate
     (subject-has-relationships ?subject ?rel1 ?intermediate)

     ;; Find permission definition that grants access via relation
     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission]
     [?perm-def :eacl.permission/relation-name ?rel2]

     ;; Find resources connected to intermediate via rel2
     [?relationship2 :eacl.relationship/subject ?intermediate]
     [?relationship2 :eacl.relationship/relation-name ?rel2]
     [?relationship2 :eacl.relationship/resource ?resource]

     ;; Verify resource type
     [?resource :resource/type ?resource-type]]

    ;; Arrow permission - optimized for subject-centric lookup
    [(has-permission ?subject ?permission ?resource-type ?resource)
     ; known: subject, permission, resource-type.
     ;; Find arrow permissions for the target resource type and permission

     [(tuple ?resource-type ?permission) ?rtype+perm]
     [?arrow :eacl.arrow-permission/resource-type+permission-name ?rtype+perm]

     ;[?arrow :eacl.arrow-permission/resource-type ?resource-type]
     ;[?arrow :eacl.arrow-permission/permission-name ?permission]
     [?arrow :eacl.arrow-permission/source-relation-name ?via-rel]
     [?arrow :eacl.arrow-permission/target-permission-name ?target-perm]

     ;; Find resources of target type
     [?resource :resource/type ?resource-type] ; this looks slow.

     ;; Find intermediate resources linked to target resource (resource is not known here)
     ; the problem is we know via-rel, but not ?intermediate or ?resource.
     ; how to cull ?resource here.
     ; we should be able to narrow down relationships here to find the relevant ?intermediate subjects for traversal

     ;[(tuple ?subject ?via-rel) ?subject+via-rel]
     ;[?link :eacl.relationship/subject+relation-name ?subject+via-rel]

     [?link :eacl.relationship/relation-name ?via-rel]
     [?link :eacl.relationship/resource ?resource]
     [?link :eacl.relationship/subject ?intermediate]

     ;; Get intermediate resource type
     [?intermediate :resource/type ?intermediate-type]

     ;; Check if subject has target permission on intermediate (recursive)
     (has-permission ?subject ?target-perm ?intermediate-type ?intermediate)]])