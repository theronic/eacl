(ns eacl.data.schema)

;; consider NS name eacl.auth.schema?

;; Example facts:

;; The core entity in EACL is the "permission", typically referred to as ?perm.
;; A permission ties together the who, what, why, when and where.

;; Example:

(def example-permission
  #:eacl{:who   [:db/ident :petrus]
         :what  [:eacl/ident :invoices]                     ;; how could this be inherited? paths?
         :why   [:eacl/ident :issue]
         :when  "tomorrow" ;; Todo: support more time concepts. Specifically timespans with one of start and end or both.
         ;; where can be any entity, most frequently used as "containers", e.g. a physical store or a place.
         :where "Cape Town"})

;; We are going to need protocols for flexible when and where.

;; can be written as:
;(defmacro dsl-serial-rule
;  [who what when where why]
;  nil)

; [ :eacl/entity ]

; []

; {:keys [who what when where why]}
; {:keys [user action location access-time why]}

;; Petrus can write stores.

; [?perm :eacl/user who]

; [ :john :eacl/allowed? role [:eacl/group ]]

(def v1-eacl-schema
  [{:db/ident       :eacl/ident
    :db/valueType   :db.type/keyword
    :db/doc         "Typically a fully-namespaced keyword for internal use, e.g. convenient for referring to core groups or key users."
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident       :eacl/path
    :db/doc         "A unique string for caching, or filesystem. Wish this was a vector."
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}

   ;{:db/ident     :eacl/entity
   ; :db/doc       "Not sure why we need this."
   ; :db/valueType :db.type/ref
   ; :db/index     true}

   ;{:db/ident       :eacl/group
   ; :db/valueType   :db.type/ref
   ; :db/cardinality :db.cardinality/many
   ; :db/index       true}

   ;{:db/ident       :eacl.role/name ;; role ident?
   ; :db/doc         "This "
   ; :db/valueType   :db.type/string
   ; :db/cardinality :db.cardinality/one
   ; :db/index       true}

   {:db/ident       :eacl/role
    :db/doc         "Role could be covered by who? Still experimenting with this."
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/allowed?
    :db/doc         "Is this action allowed (true) or denied (false)? When not present."
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/index       true}                                   ;; hmm? access code? vs :eacl/permission?

   {:db/ident       :eacl/who
    :db/doc         "EACL user."
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/what
    :db/doc         "The 'what' can be the resource being accessed."
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/why
    :db/doc         "Optional. The 'why' is like 'reason', e.g. 'create invoice <so that I can bill them>.' Useful for conditionally allowing things based on consequents or one-time reads."
    :db/valueType   :db.type/keyword                                ;; vs string?
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/when
    :db/doc         "When does this apply.  this was or can be accessed. How to handle future?"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/where
    :db/doc         "The 'where' can refer to a logical or physical location."
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/how
    :db/doc         "The 'how' is like 'action', e.g. read/write.'"
    :db/valueType   :db.type/keyword                                ;; vs string?
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/parent
    :db/doc         "For general inheritance, e.g. group belongs to a supergroup."
    :db/valueType   :db.type/ref
    ;; just one cardinality for now. What would the implications be of multi-cardinality?
    ;; Use :eacl/group for multi-cardinality. One parent allows us to have predictable nesting.
    :db/cardinality :db.cardinality/one ;; multiple?
    :db/index       true}

   ;; why not `:eacl/when` ? because who/why/what/when.
   ;; How to distinguish access log?
   {:db/ident       :eacl/access-time
    :db/index       true
    :db/doc         "When this was or can be accessed."
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/instant}])
