(ns eacl.relationships.storage
  "Canonical relationship-storage attribute identities shared by every backend.

  The `v7` keyword namespace is the persisted storage ABI, not an executable
  v7 engine contract. EACL v8 retains it so existing tuple data does not need
  to be rewritten merely to upgrade the library.")

(def forward-attribute
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def reverse-attribute
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(def attributes
  #{forward-attribute reverse-attribute})
