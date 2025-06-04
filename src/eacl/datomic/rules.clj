(ns eacl.datomic.rules)

(def check-permission-rules
  "Can only be used for can? where type + id of both subject & resource object are provided."
  '[;; Reachability rules to traverse relationships:
    [(reachable ?resource ?subject)
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]

     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?subject]]
    [(reachable ?resource ?subject)
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?mid]
     (reachable ?mid ?subject)] ; note inversion to traverse.

    ;; Direct permission check (copied and adapted from core2)
    [(has-permission ?subject ?permission-name ?resource)

     [(tuple ?resource ?relation-name-in-tuple ?subject) ?resource+rel-name+subject]
     [?relationship :eacl.relationship/resource+relation-name+subject ?resource+rel-name+subject]

     [?relationship :eacl.relationship/resource ?resource]  ; subject has some relationship TO the resource
     [?relationship :eacl.relationship/relation-name ?relation-name-in-tuple]
     [?relationship :eacl.relationship/subject ?subject]    ; subject of the relationship tuple

     ;; Permission definition: ?relation-name-in-perm-def grants ?permission-name on ?resource-type
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; THIS IS THE DIRECT GRANT

     ;; Match the relation name from the relationship tuple with the one in permission definition
     [(= ?relation-name-in-tuple ?relation-name-in-perm-def)]
     [(not= ?subject ?resource)]]

    ;; Indirect permission inheritance (copied from core2 - may need review/replacement with arrows)
    ;; This rule means: ?subject gets ?permission-name on ?resource if:
    ;; 1. A permission definition exists: for ?resource-type, ?relation-name-in-perm-def grants ?permission-name.
    ;; 2. ?resource has a relationship (as a subject of the tuple) via ?relation-name-in-perm-def to some ?target.
    ;;    (e.g. doc D is "subject" of relation "group" to group G: D --group--> G)
    ;; 3. ?subject can "reach" that ?target (e.g. user U is member of group G).

    [(has-permission ?subject ?permission-name ?resource)
     ;; Permission definition
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     ; can these move down for speed?
     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; Direct relation specified in perm

     ;; Structural relationship: ?resource is linked to ?target via ?relation-name-in-perm-def
     [(tuple ?target ?relation-name-in-perm-def ?resource) ?target+relation+resource]
     [?structural-rel :eacl.relationship/resource+relation-name+subject ?target+relation+resource]

     [?structural-rel :eacl.relationship/subject ?resource]
     [?structural-rel :eacl.relationship/relation-name ?relation-name-in-perm-def]
     [?structural-rel :eacl.relationship/resource ?target]

     (reachable ?target ?subject)                           ; User must be able to reach the target of the structural relationship
     [(not= ?subject ?resource)]]

    ;; Arrow permission rule: ?subject gets ?perm-name-on-this-resource if it has ?perm-name-on-related on an intermediate resource
    ;; Example: User U gets :admin on VPC_X if VPC_X --:account--> ACC_Y and User U has :admin on ACC_Y.
    ;; MODIFIED based on user feedback: Rule now expects intermediate --via-relation-name--> this-resource
    ;; Example: User U gets :view on SERVER_X if ACC_Y --:account--> SERVER_X and User U has :admin on ACC_Y.
    [(has-permission ?subject ?perm-name-on-this-resource ?this-resource)
     ;; 1. Find an arrow permission definition for this-resource-type and perm-name-on-this-resource
     [(tuple ?this-resource-type
             ?via-relation-name
             ?perm-on-related
             ?perm-name-on-this-resource) ?res-type+relation+related-perm+permission]
     [?arrow-perm-def
      :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name
      ?res-type+relation+related-perm+permission]

     [?arrow-perm-def :eacl.arrow-permission/resource-type ?this-resource-type]
     [?arrow-perm-def :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm-def :eacl.arrow-permission/source-relation-name ?via-relation-name] ; e.g., :account (the relation name specified in Permission)
     [?arrow-perm-def :eacl.arrow-permission/target-permission-name ?perm-on-related] ; e.g., :admin (on the intermediate/account)

     ;; 2. Find intermediate resource: ?intermediate-resource --via-relation-name--> ?this-resource
     [(tuple ?this-resource ?via-relation-name ?intermediate-resource) ?resource+relation+mid-resource]
     [?rel-linking-resources :eacl.relationship/resource+relation-name+subject ?resource+relation+mid-resource]
     [?rel-linking-resources :eacl.relationship/subject ?intermediate-resource] ; e.g., account is subject of tuple
     [?rel-linking-resources :eacl.relationship/relation-name ?via-relation-name] ; relation is :account
     [?rel-linking-resources :eacl.relationship/resource ?this-resource] ; e.g., server/vpc is resource of tuple

     ;; 3. Subject must have the target permission on the intermediate resource (recursive call)
     (has-permission ?subject ?perm-on-related ?intermediate-resource)
     [(not= ?subject ?this-resource)]                       ; Exclude self-references for safety
     ;; Ensure the intermediate resource is not the same as the subject to prevent some loops,
     ;; though main cycle prevention relies on data structure or more complex rule logic if needed.
     [(not= ?subject ?intermediate-resource)]
     ;; Ensure this-resource is not the same as intermediate for simple arrows like A -> B
     [(not= ?this-resource ?intermediate-resource)]]])

(defn build-slow-rules [resource-type-attr]
  [;; Reachability rules to traverse relationships:
   '[(reachable ?resource ?subject)
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]]

   ;[?relationship :eacl.relationship/resource ?resource]
   ;[?relationship :eacl.relationship/subject ?subject]]
   '[(reachable ?resource ?subject)

     [(tuple ?resource ?mid) ?resource+mid]
     [?relationship :eacl.relationship/resource+subject ?resource+mid] ; range query?

     ; todo can we use tuple here?
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?mid]
     (reachable ?mid ?subject)]

   ;; Direct permission check (copied and adapted from core2)
   '[(has-permission ?subject ?permission-name ?resource)

     [(tuple ?resource ?relation-name-in-tuple ?subject) ?resource+rel-name+subject]
     [?relationship :eacl.relationship/resource+relation-name+subject ?resource+rel-name+subject]

     [?relationship :eacl.relationship/resource ?resource]  ; subject has some relationship TO the resource
     [?relationship :eacl.relationship/relation-name ?relation-name-in-tuple]
     [?relationship :eacl.relationship/subject ?subject]    ; subject of the relationship tuple

     ;; Permission definition: ?relation-name-in-perm-def grants ?permission-name on ?resource-type
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; THIS IS THE DIRECT GRANT

     ;; Match the relation name from the relationship tuple with the one in permission definition
     [(= ?relation-name-in-tuple ?relation-name-in-perm-def)]
     [(not= ?subject ?resource)]                            ; can we avoid this?
     [?resource :resource/type ?resource-type]]             ; this is super slow. different rules WIP.

   ;; Indirect permission inheritance (copied from core2 - may need review/replacement with arrows)
   ;; This rule means: ?subject gets ?permission-name on ?resource if:
   ;; 1. A permission definition exists: for ?resource-type, ?relation-name-in-perm-def grants ?permission-name.
   ;; 2. ?resource has a relationship (as a subject of the tuple) via ?relation-name-in-perm-def to some ?target.
   ;;    (e.g. doc D is "subject" of relation "group" to group G: D --group--> G)
   ;; 3. ?subject can "reach" that ?target (e.g. user U is member of group G).
   (into
     ['(has-permission ?subject ?permission-name ?resource)]

     '[;; Permission definition
       [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
       [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

       [?perm-def :eacl.permission/resource-type ?resource-type]
       [?perm-def :eacl.permission/permission-name ?permission-name]
       [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; Direct relation specified in perm

       ;; Structural relationship: ?resource is linked to ?target via ?relation-name-in-perm-def
       [(tuple ?target ?relation-name-in-perm-def ?resource) ?target+relation+resource]
       [?structural-rel :eacl.relationship/resource+relation-name+subject ?target+relation+resource]

       [?structural-rel :eacl.relationship/subject ?resource]
       [?structural-rel :eacl.relationship/relation-name ?relation-name-in-perm-def]
       [?structural-rel :eacl.relationship/resource ?target]

       (reachable ?target ?subject)                         ; User must be able to reach the target of the structural relationship
       [(not= ?subject ?resource)]
       [?resource :resource/type ?resource-type]])

   ;; Arrow permission rule: ?subject gets ?perm-name-on-this-resource if it has ?perm-name-on-related on an intermediate resource
   ;; Example: User U gets :admin on VPC_X if VPC_X --:account--> ACC_Y and User U has :admin on ACC_Y.
   ;; MODIFIED based on user feedback: Rule now expects intermediate --via-relation-name--> this-resource
   ;; Example: User U gets :view on SERVER_X if ACC_Y --:account--> SERVER_X and User U has :admin on ACC_Y.
   '[(has-permission ?subject ?perm-name-on-this-resource ?this-resource)

     ;; 1. Find an arrow permission definition for this-resource-type and perm-name-on-this-resource
     [(tuple ?this-resource-type
             ?via-relation-name
             ?perm-on-related
             ?perm-name-on-this-resource) ?res-type+relation+related-perm+permission]
     [?arrow-perm-def
      :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name
      ?res-type+relation+related-perm+permission]

     ; can these move down for speed, or be decoupled in a 2nd phase?
     [?arrow-perm-def :eacl.arrow-permission/resource-type ?this-resource-type]
     [?arrow-perm-def :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm-def :eacl.arrow-permission/source-relation-name ?via-relation-name] ; e.g., :account (the relation name specified in Permission)
     [?arrow-perm-def :eacl.arrow-permission/target-permission-name ?perm-on-related] ; e.g., :admin (on the intermediate/account)

     ;; 2. Find intermediate resource: ?intermediate-resource --via-relation-name--> ?this-resource
     [(tuple ?this-resource ?via-relation-name ?intermediate-resource) ?resource+relation+mid-resource]
     [?rel-linking-resources :eacl.relationship/resource+relation-name+subject ?resource+relation+mid-resource]

     [?rel-linking-resources :eacl.relationship/subject ?intermediate-resource] ; e.g., account is subject of tuple
     [?rel-linking-resources :eacl.relationship/relation-name ?via-relation-name] ; relation is :account
     [?rel-linking-resources :eacl.relationship/resource ?this-resource] ; e.g., server/vpc is resource of tuple

     ;; 3. Subject must have the target permission on the intermediate resource (recursive call)
     (has-permission ?subject ?perm-on-related ?intermediate-resource)
     [(not= ?subject ?this-resource)]                       ; Exclude self-references for safety
     ;; Ensure the intermediate resource is not the same as the subject to prevent some loops,
     ;; though main cycle prevention relies on data structure or more complex rule logic if needed.
     [(not= ?subject ?intermediate-resource)]
     ;; Ensure this-resource is not the same as intermediate for simple arrows like A -> B
     [(not= ?this-resource ?intermediate-resource)]
     [?this-resource :resource/type ?this-resource-type]]]) ; this is super slow. different rules WIP.]])

(def slow-lookup-rules (build-slow-rules :resource/type))

(def rules-lookup-subjects
  '[;; Reachability rules to traverse relationships:
    [(reachable ?resource ?subject) ; I think we need types here for speed.
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]

     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?subject]]

    [(reachable ?resource ?subject)

     [(tuple ?resource ?mid) ?resource+mid]
     [?relationship :eacl.relationship/resource+subject ?resource+mid] ; range query?

     ; todo can we use tuple here?
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?mid]
     (reachable ?mid ?subject)]

    ;; Direct permission check (copied and adapted from core2)
    [(has-permission ?subject-type ?subject ?permission-name ?resource)

     [(tuple ?resource ?relation-name-in-tuple ?subject) ?resource+rel-name+subject]
     [?relationship :eacl.relationship/resource+relation-name+subject ?resource+rel-name+subject]

     [?relationship :eacl.relationship/resource ?resource]  ; subject has some relationship TO the resource
     [?relationship :eacl.relationship/relation-name ?relation-name-in-tuple]
     [?relationship :eacl.relationship/subject ?subject]    ; subject of the relationship tuple

     ;; Permission definition: ?relation-name-in-perm-def grants ?permission-name on ?resource-type
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; THIS IS THE DIRECT GRANT

     ;; Match the relation name from the relationship tuple with the one in permission definition
     [(= ?relation-name-in-tuple ?relation-name-in-perm-def)]
     [(not= ?subject ?resource)]                            ; can we avoid this?
     [?subject :resource/type ?subject-type]]             ; this is super slow. different rules WIP.

    ;; Indirect permission inheritance (copied from core2 - may need review/replacement with arrows)
    ;; This rule means: ?subject gets ?permission-name on ?resource if:
    ;; 1. A permission definition exists: for ?resource-type, ?relation-name-in-perm-def grants ?permission-name.
    ;; 2. ?resource has a relationship (as a subject of the tuple) via ?relation-name-in-perm-def to some ?target.
    ;;    (e.g. doc D is "subject" of relation "group" to group G: D --group--> G)
    ;; 3. ?subject can "reach" that ?target (e.g. user U is member of group G).

    [(has-permission ?subject-type ?subject ?permission-name ?resource)
     ;; Permission definition
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; Direct relation specified in perm

     ;; Structural relationship: ?resource is linked to ?target via ?relation-name-in-perm-def
     [(tuple ?target ?relation-name-in-perm-def ?resource) ?target+relation+resource]
     [?structural-rel :eacl.relationship/resource+relation-name+subject ?target+relation+resource]

     [?structural-rel :eacl.relationship/subject ?resource]
     [?structural-rel :eacl.relationship/relation-name ?relation-name-in-perm-def]
     [?structural-rel :eacl.relationship/resource ?target]

     (reachable ?target ?subject)                           ; User must be able to reach the target of the structural relationship
     [(not= ?subject ?resource)]
     [?subject :resource/type ?subject-type]]

    ;; Arrow permission rule: ?subject gets ?perm-name-on-this-resource if it has ?perm-name-on-related on an intermediate resource
    ;; Example: User U gets :admin on VPC_X if VPC_X --:account--> ACC_Y and User U has :admin on ACC_Y.
    ;; MODIFIED based on user feedback: Rule now expects intermediate --via-relation-name--> this-resource
    ;; Example: User U gets :view on SERVER_X if ACC_Y --:account--> SERVER_X and User U has :admin on ACC_Y.
    [(has-permission ?subject-type ?subject ?perm-name-on-this-resource ?this-resource)

     ; this order looks wrong.
     ;; 1. Find an arrow permission definition for this-resource-type and perm-name-on-this-resource
     [(tuple ?this-resource-type
             ?via-relation-name
             ?perm-on-related
             ?perm-name-on-this-resource) ?res-type+relation+related-perm+permission]
     [?arrow-perm-def
      :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name
      ?res-type+relation+related-perm+permission]

     ; can these move down for speed, or be decoupled in a 2nd phase?
     [?arrow-perm-def :eacl.arrow-permission/resource-type ?this-resource-type]
     [?arrow-perm-def :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm-def :eacl.arrow-permission/source-relation-name ?via-relation-name] ; e.g., :account (the relation name specified in Permission)
     [?arrow-perm-def :eacl.arrow-permission/target-permission-name ?perm-on-related] ; e.g., :admin (on the intermediate/account)

     ;; 2. Find intermediate resource: ?intermediate-resource --via-relation-name--> ?this-resource
     [(tuple ?this-resource ?via-relation-name ?intermediate-resource) ?resource+relation+mid-resource]
     [?rel-linking-resources :eacl.relationship/resource+relation-name+subject ?resource+relation+mid-resource]

     [?rel-linking-resources :eacl.relationship/subject ?intermediate-resource] ; e.g., account is subject of tuple
     [?rel-linking-resources :eacl.relationship/relation-name ?via-relation-name] ; relation is :account
     [?rel-linking-resources :eacl.relationship/resource ?this-resource] ; e.g., server/vpc is resource of tuple

     ;; 3. Subject must have the target permission on the intermediate resource (recursive call)
     (has-permission ?subject-type ?subject ?perm-on-related ?intermediate-resource)
     [(not= ?subject ?this-resource)]                       ; Exclude self-references for safety
     ;; Ensure the intermediate resource is not the same as the subject to prevent some loops,
     ;; though main cycle prevention relies on data structure or more complex rule logic if needed.
     [(not= ?subject ?intermediate-resource)]
     ;; Ensure this-resource is not the same as intermediate for simple arrows like A -> B
     [(not= ?this-resource ?intermediate-resource)]
     ; TODO: this-resource looks dubious here. do we need it?
     [?this-resource :resource/type ?this-resource-type]]])

(def rules-lookup-resources
  ; resource look has known subject Type + ID, and known resource type.
  '[;; Reachability rules to traverse relationships:
    [(reachable ?resource ?subject) ; I think we need types here for speed.
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]

     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?subject]]

    [(reachable ?resource ?subject)

     [(tuple ?resource ?mid) ?resource+mid]
     [?relationship :eacl.relationship/resource+subject ?resource+mid] ; range query?

     ; todo can we use tuple here?
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?mid]
     (reachable ?mid ?subject)]

    ;; Direct permission check (copied and adapted from core2)
    [(has-permission ?subject ?permission-name ?resource-type ?resource)

     [(tuple ?resource ?relation-name-in-tuple ?subject) ?resource+rel-name+subject]
     [?relationship :eacl.relationship/resource+relation-name+subject ?resource+rel-name+subject]

     [?relationship :eacl.relationship/resource ?resource]  ; subject has some relationship TO the resource
     [?relationship :eacl.relationship/relation-name ?relation-name-in-tuple]
     [?relationship :eacl.relationship/subject ?subject]    ; subject of the relationship tuple

     ;; Permission definition: ?relation-name-in-perm-def grants ?permission-name on ?resource-type
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; THIS IS THE DIRECT GRANT

     ;; Match the relation name from the relationship tuple with the one in permission definition
     [(= ?relation-name-in-tuple ?relation-name-in-perm-def)]
     [(not= ?subject ?resource)]                            ; can we avoid this?
     [?resource :resource/type ?resource-type]]

    ;; Indirect permission inheritance (copied from core2 - may need review/replacement with arrows)
    ;; This rule means: ?subject gets ?permission-name on ?resource if:
    ;; 1. A permission definition exists: for ?resource-type, ?relation-name-in-perm-def grants ?permission-name.
    ;; 2. ?resource has a relationship (as a subject of the tuple) via ?relation-name-in-perm-def to some ?target.
    ;;    (e.g. doc D is "subject" of relation "group" to group G: D --group--> G)
    ;; 3. ?subject can "reach" that ?target (e.g. user U is member of group G).

    [(has-permission ?subject ?permission-name ?resource-type ?resource)
     ;; Permission definition
     ;; order looks dubious
     [(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]

     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; Direct relation specified in perm

     ;; Structural relationship: ?resource is linked to ?target via ?relation-name-in-perm-def
     [(tuple ?target ?relation-name-in-perm-def ?resource) ?target+relation+resource]
     [?structural-rel :eacl.relationship/resource+relation-name+subject ?target+relation+resource]

     [?structural-rel :eacl.relationship/subject ?resource]
     [?structural-rel :eacl.relationship/relation-name ?relation-name-in-perm-def]
     [?structural-rel :eacl.relationship/resource ?target]

     (reachable ?target ?subject)                           ; User must be able to reach the target of the structural relationship
     [(not= ?subject ?resource)]
     [?resource :resource/type ?this-resource-type]] ; is this correct?

    ;; Arrow permission rule: ?subject gets ?perm-name-on-this-resource if it has ?perm-name-on-related on an intermediate resource
    ;; Example: User U gets :admin on VPC_X if VPC_X --:account--> ACC_Y and User U has :admin on ACC_Y.
    ;; MODIFIED based on user feedback: Rule now expects intermediate --via-relation-name--> this-resource
    ;; Example: User U gets :view on SERVER_X if ACC_Y --:account--> SERVER_X and User U has :admin on ACC_Y.
    [(has-permission ?subject ?perm-name-on-this-resource ?this-resource-type ?this-resource)

     ;; 1. Find an arrow permission definition for this-resource-type and perm-name-on-this-resource
     [(tuple ?this-resource-type
             ?via-relation-name
             ?perm-on-related
             ?perm-name-on-this-resource) ?res-type+relation+related-perm+permission]
     [?arrow-perm-def
      :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name
      ?res-type+relation+related-perm+permission]

     ; can these move down for speed, or be decoupled in a 2nd phase?
     [?arrow-perm-def :eacl.arrow-permission/resource-type ?this-resource-type]
     [?arrow-perm-def :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm-def :eacl.arrow-permission/source-relation-name ?via-relation-name] ; e.g., :account (the relation name specified in Permission)
     [?arrow-perm-def :eacl.arrow-permission/target-permission-name ?perm-on-related] ; e.g., :admin (on the intermediate/account)

     ;; 2. Find intermediate resource: ?intermediate-resource --via-relation-name--> ?this-resource
     [(tuple ?this-resource ?via-relation-name ?intermediate-resource) ?resource+relation+mid-resource]
     [?rel-linking-resources :eacl.relationship/resource+relation-name+subject ?resource+relation+mid-resource]

     [?rel-linking-resources :eacl.relationship/subject ?intermediate-resource] ; e.g., account is subject of tuple
     [?rel-linking-resources :eacl.relationship/relation-name ?via-relation-name] ; relation is :account
     [?rel-linking-resources :eacl.relationship/resource ?this-resource] ; e.g., server/vpc is resource of tuple

     [?intermediate-resource :resource/type ?intermediate-resource-type] ; do we need this?

     ;; 3. Subject must have the target permission on the intermediate resource (recursive call)
     (has-permission ?subject ?perm-on-related ?intermediate-resource-type ?intermediate-resource)
     [(not= ?subject ?this-resource)]                       ; Exclude self-references for safety
     ;; Ensure the intermediate resource is not the same as the subject to prevent some loops,
     ;; though main cycle prevention relies on data structure or more complex rule logic if needed.
     [(not= ?subject ?intermediate-resource)]
     ;; Ensure this-resource is not the same as intermediate for simple arrows like A -> B
     [(not= ?this-resource ?intermediate-resource)]
     [?this-resource :resource/type ?this-resource-type]]])

;(def rules-lookup-subjects
;  ; lookup-subjects knows resource & subject type which implies knowing resource type.
;  '[;; Reachability rules to traverse relationships:
;    [(reachable ?resource ?subject)
;     ; todo use reachable tuple
;     [(tuple ?resource ?subject) ?resource+subject]
;     [?relationship :eacl.relationship/resource+subject ?resource+subject]
;
;     [?relationship :eacl.relationship/resource ?resource]
;     [?relationship :eacl.relationship/subject ?subject]]
;
;    [(reachable ?resource ?subject)
;     ; don't think this will work...
;     [(tuple ?resource ?mid) ?resource+mid]
;     [?relationship :eacl.relationship/resource+subject ?resource+mid]
;
;     [?relationship :eacl.relationship/resource ?resource]
;     [?relationship :eacl.relationship/subject ?mid]
;     (reachable ?mid ?subject)]
;
;    ;; Direct permission check
;    [(has-permission ?resource ?permission-name ?subject-type ?subject)
;
;     [(tuple ?resource ?relation-name-in-tuple ?subject) ?resource+rel-name+subject]
;     [?relationship :eacl.relationship/resource+relation-name+subject ?resource+rel-name+subject]
;
;     [?relationship :eacl.relationship/resource ?resource]  ; subject has some relationship TO the resource
;     [?relationship :eacl.relationship/relation-name ?relation-name-in-tuple]
;     [?relationship :eacl.relationship/subject ?subject]    ; subject of the relationship tuple
;
;     ;; Permission definition: ?relation-name-in-perm-def grants ?permission-name on ?resource-type
;     [(tuple ?resource-type ?relation-name-in-tuple ?permission-name) ?res-type+relation+permission]
;     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]
;
;     [?perm-def :eacl.permission/resource-type ?resource-type]
;     [?perm-def :eacl.permission/permission-name ?permission-name]
;     [?perm-def :eacl.permission/relation-name ?relation-name-in-tuple] ; THIS IS THE DIRECT GRANT
;
;     ;; Match the relation name from the relationship tuple with the one in permission definition
;     ;[(= ?relation-name-in-tuple ?relation-name-in-perm-def)] ; can this be unified better? should this be higher up?
;
;     [(not= ?subject ?resource)]]
;
;    ;; Indirect permission inheritance (copied from core2 - may need review/replacement with arrows)
;    ;; This rule means: ?subject gets ?permission-name on ?resource if:
;    ;; 1. A permission definition exists: for ?resource-type, ?relation-name-in-perm-def grants ?permission-name.
;    ;; 2. ?resource has a relationship (as a subject of the tuple) via ?relation-name-in-perm-def to some ?target.
;    ;;    (e.g. doc D is "subject" of relation "group" to group G: D --group--> G)
;    ;; 3. ?subject can "reach" that ?target (e.g. user U is member of group G).
;
;    [(has-permission ?resource ?permission-name ?subject-type ?subject)
;     ;; Permission definition
;     ;; I don't think tuple makes sense here...
;     ;[(tuple ?resource-type ?relation-name-in-perm-def ?permission-name) ?res-type+relation+permission]
;     ;[?perm-def :eacl.permission/resource-type+relation-name+permission-name ?res-type+relation+permission]
;
;     [?perm-def :eacl.permission/resource-type ?resource-type]
;     [?perm-def :eacl.permission/permission-name ?permission-name]
;     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; Direct relation specified in perm
;
;     ;; Structural relationship: ?resource is linked to ?target via ?relation-name-in-perm-def
;     [(tuple ?target ?relation-name-in-perm-def ?resource) ?target+relation+resource]
;     [?structural-rel :eacl.relationship/resource+relation-name+subject ?target+relation+resource]
;
;     [?structural-rel :eacl.relationship/subject ?resource]
;     [?structural-rel :eacl.relationship/relation-name ?relation-name-in-perm-def]
;     [?structural-rel :eacl.relationship/resource ?target]
;
;     (reachable ?target ?subject)                           ; User must be able to reach the target of the structural relationship
;     [?resource :resource/type ?resource-type]             ; super slow. different rules WIP.
;     [?subject :resource/type ?subject-type]
;     [(not= ?subject ?resource)]]
;
;    ;; Arrow permission rule: ?subject gets ?perm-name-on-this-resource if it has ?perm-name-on-related on an intermediate resource
;    ;; Example: User U gets :admin on VPC_X if VPC_X --:account--> ACC_Y and User U has :admin on ACC_Y.
;    ;; MODIFIED based on user feedback: Rule now expects intermediate --via-relation-name--> this-resource
;    ;; Example: User U gets :view on SERVER_X if ACC_Y --:account--> SERVER_X and User U has :admin on ACC_Y.
;    [(has-permission ?this-resource ?perm-name-on-this-resource ?subject-type ?subject)
;
;     ;; 1. Find an arrow permission definition for this-resource-type and perm-name-on-this-resource
;     [(tuple ?this-resource-type
;             ?via-relation-name
;             ?perm-on-related
;             ?perm-name-on-this-resource) ?res-type+relation+related-perm+permission]
;     [?arrow-perm-def
;      :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name
;      ?res-type+relation+related-perm+permission]
;
;     ; can these move down for speed, or be decoupled in a 2nd phase?
;     [?arrow-perm-def :eacl.arrow-permission/resource-type ?this-resource-type]
;     [?arrow-perm-def :eacl.arrow-permission/permission-name ?perm-name-on-this-resource]
;     [?arrow-perm-def :eacl.arrow-permission/source-relation-name ?via-relation-name] ; e.g., :account (the relation name specified in Permission)
;     [?arrow-perm-def :eacl.arrow-permission/target-permission-name ?perm-on-related] ; e.g., :admin (on the intermediate/account)
;
;     ;; 2. Find intermediate resource: ?intermediate-resource --via-relation-name--> ?this-resource
;     [(tuple ?this-resource ?via-relation-name ?intermediate-resource) ?resource+relation+mid-resource]
;     [?rel-linking-resources :eacl.relationship/resource+relation-name+subject ?resource+relation+mid-resource]
;
;     [?rel-linking-resources :eacl.relationship/subject ?intermediate-resource] ; e.g., account is subject of tuple
;     [?rel-linking-resources :eacl.relationship/relation-name ?via-relation-name] ; relation is :account
;     [?rel-linking-resources :eacl.relationship/resource ?this-resource] ; e.g., server/vpc is resource of tuple
;
;     ;[?this-resource :resource/type ?this-resource-type]
;     ;[?subject :resource/type ?subject-type]
;     [?intermediate-resource :resource/type ?intermediate-resource-type]
;
;     ;; 3. Subject must have the target permission on the intermediate resource (recursive call)
;     (has-permission ?subject ?perm-on-related ?intermediate-resource-type ?intermediate-resource)
;     [(not= ?subject ?this-resource)]                       ; Exclude self-references for safety
;     ;; Ensure the intermediate resource is not the same as the subject to prevent some loops,
;     ;; though main cycle prevention relies on data structure or more complex rule logic if needed.
;     [(not= ?subject ?intermediate-resource)]
;     ;; Ensure this-resource is not the same as intermediate for simple arrows like A -> B
;     [(not= ?this-resource ?intermediate-resource)]]])
; this is super slow. different rules WIP.]])
