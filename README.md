# **EACL**: Enterprise Access Control

## How to Run All Tests

```shell
clj -X:test
```
## Run Test for One Namespace

```bash
clj -M:test -n eacl.core3-test
```

## Run Tests for Multiple Namespaces

```bash
clj -X:test :nses '["my-namespace1" "my-namespace2"]'
```

Note difference between `-M` & `-X` switches.

## Run a single test (under deftest)

```bash
clojure -M:test -v my.namespace/test-name
```


Project Status: Alpha.

EACL is a Datomic-based authorization system based on [SpiceDB](https://authzed.com/spicedb).

- There are Subjects & Resources.
- Resources have Relations, e.g. `:product/owner`, which confers a set of permissions on a Subject, e.g. 
  `:product/view`, `:product/delete`.
- Subjects & Resources are connected via Relationships.
- Relationships have `{:keys [subject relation resource]}`, e.g. `(Relationship :user/joe :product/owner :test/product1)`.

If a Resource is reachable by a Subject via a Relation, the permissions from that Relation are conferred on the subject.

# API

```clojure
(can? db subject permission resource) => true | false
```

E.g.
```clojure
(can? db :test/user :product/view :test/product) => true | false
```

Todo better docs, but there's also:

- (lookup-subjects ...)
- (lookup-resources ...)

# Limitations

EACL makes no cliams about being fast.
EACL does not support ZedTokens ala Zookies, but it is flexible.
SpiceDB is heavily optimised to maintain a consistent cache.

Note that `subject.relation` is not currently supported.

# Licence

I don't know what Licence to use for this, so I slapped a BSL on it.

Just ask if you want to use it – I'm generally happy to open-source it.