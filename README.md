# **EACL**: Enterprise Access Control

Project Status: Alpha.

EACL is a Datomic-based authorization system based on [SpiceDB](https://authzed.com/spicedb).

- There are Subjects & Resources. 
- Resources have Relations, e.g. `:product/owner`, which confers a set of permissions on a Subject, e.g. 
  `:product/view`, `:product/delete`.
- Subjects & Resources are connected via Relationships.
- Relationships have `{:keys [subject relation resource]}`, e.g. `(Relationship :user/joe :product/owner :test/product1)`.

If a Resource is reachable by a Subject via a Relation, the permissions from that Relation are conferred on the subject.

The most annoying part of the design right now is that you have to specify all relevant resources on a relation, because we don't have resource types like SpiceDB, or a collective grouping like 'products'.

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