(ns eacl.schema-fixtures)

(def schema-empty "") ; should this be allowed?

; todo move to fixtures
(def schema-user+document-relations-only
  "
definition user {}
definition document {}
  ")

(def schema-user+document
  "
definition user {}
definition document {
  relation owner: user
  permission admin = owner
}
")

(def schema-user+account+document-permission
  "
definition user {}

definition account {
  relation owner: user
}

definition document {
  relation account: account
  permission admin = account->owner
}
")

(def schema-user+document-arrow-permission
  "
definition user {}

definition account {
  relation owner: user
  permission admin = owner
}

definition document {
  relation account: account
  permission admin = account->admin
}
")

(def schema-platform+user+account+document-union-permission
  "
definition user {}

definition platform {
  relation super_admin: user
}

definition account {
  relation platform: platform
  relation owner: user
  permission admin = owner + platform->super_admin
}

definition document {
  relation account: account
  permission admin = account->admin
}
")