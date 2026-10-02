# Caveats and expiring relationships

A relationship can have an expiration time, a condition (called a Caveat), or
both. Expiration alone needs no evaluator or extra dependency.

## Expiring access

This recipe continues the [Datomic quickstart](../README.md#quickstart), before
its deletion step. `alice` and `report` are existing application entities.
Give Alice access for one hour:

```clojure
(def deadline-ms (+ (System/currentTimeMillis) 3600000))
(def share (eacl/->Relationship alice :viewer report))

(eacl/write-relationship! acl
  (assoc share :operation :touch :valid-until-ms deadline-ms))
(eacl/can? acl alice :view report) ; true until the deadline
```

`:valid-until-ms` is an exclusive deadline in UTC milliseconds since the Unix
epoch. This share grants access while `now < deadline-ms`. At the deadline it
stops granting access, even if nothing is written to the database. Other
shares may still give access, including a role inherited from a folder.

For a date and time selected in the UI:

1. Interpret the input in the user's selected time zone. A local date and time
   alone does not identify an instant; handle ambiguous daylight-saving times.
2. Convert that instant to UTC milliseconds and send it as `:valid-until-ms`.
3. Display the saved instant in the user's time zone.
4. Leave the field out for access with no end date.

The server chooses the time used for authorization. Do not accept a browser's
clock as the evaluation time. A cache time limit does not set share expiration.

## Updating a share

The subject, relation, and resource identify a share. A different expiration
does not create a second share. An expired share remains stored, so `:create`
still conflicts with it. Use `:touch` to create or update that share:

```clojure
;; Renew for another hour, including when the saved share has expired.
(eacl/write-relationship! acl
  (assoc share :operation :touch
         :valid-until-ms (+ (System/currentTimeMillis) 3600000)))

;; Clear the expiration. This also removes any Caveat on this relationship.
(eacl/write-relationship! acl (assoc share :operation :touch))

;; Remove this share entirely.
(eacl/delete-relationship! acl share)
```

To change roles, delete the old relation and touch the new one in one batch.
The permission schema must allow both roles. This example changes `:viewer`
to `:owner`, as defined in the quickstart:

```clojure
(eacl/write-relationships! acl
  [{:operation :delete :relationship share}
   {:operation    :touch
    :relationship (eacl/->Relationship alice :owner report)}])
```

If the new role should expire, put `:valid-until-ms` on its relationship.
For a same-role update, use one `:touch` rather than deleting and creating
the same relationship in a batch.

## Showing saved shares and current access

`read-relationships` returns saved relationships, including expired ones.
`can?` answers whether a permission is currently granted, including inherited
access. Use both when a sharing screen needs to explain the difference.

Authorize the caller before returning sharing metadata. Do the check and read
on the same snapshot:

```clojure
(defn saved-shares [acl caller document]
  (eacl/with-snapshot [s (eacl/snapshot acl)]
    (when-not (eacl/can? s caller :share document)
      (throw (ex-info "You cannot manage sharing for this document."
                      {:type :app/forbidden})))
    (eacl/read-relationships s
      {:resource/type (:type document)
       :resource/id   (:id document)
       :first         50})))
```

The quickstart's viewer cannot manage sharing; its owner can. Follow
`:page-info` to read more than one page. `:relationship-state :expiry-active`
excludes expired rows, but does not prove that a Caveat is satisfied or that
the caller may see the row.

A screen that refreshes only after database transactions will miss expiration.
Refresh at the next displayed deadline, or poll. Check current access on every
protected server request. Acquire a fresh snapshot for each refresh: a retained
snapshot keeps its original evaluation time. If a cursor returns
`:eacl.pagination/restart-required` after a deadline, start the lookup again
without that cursor.

The [Datomic consumer example](examples/datomic-consumer/) verifies expiration,
renewal, clearing, role changes, and inherited access against the published
release. The [DataScript example](examples/caveats.clj) also covers named
conditions and requires the optional JVM evaluator.

## Conditional access

Use a Caveat when access depends on request data, such as the user's region.
A Caveat names a Boolean condition and the inputs it needs. Named conditions
need an optional evaluator: `eacl-caveats-jvm` on the JVM, or
`eacl-caveats-portable` in ClojureScript, including DataScript apps. See the
[JVM](../modules/eacl-caveats-jvm/README.md) and
[ClojureScript](../modules/eacl-caveats-portable/README.md) evaluator setup.

## Named definitions and schema admission

Add the evaluator alongside your backend dependency, at the same version:

```clojure
{:deps {dev.eacl/eacl-datomic     {:mvn/version "8.0.0-RC-2026-09-12"}
        dev.eacl/eacl-caveats-jvm {:mvn/version "8.0.0-RC-2026-09-12"}}}
```

Require it before writing a schema that uses a named condition:

```clojure
(require '[eacl.caveats.jvm])
```

Requiring this namespace registers the JVM evaluator. In ClojureScript,
require `eacl.caveats.portable` instead; its
[README](../modules/eacl-caveats-portable/README.md) shows the dependency.
Use `eacl/write-schema!` to install a schema such as:

```zed
caveat in_region(region string, accepted list<string>) {
  region in accepted
}

definition user {}
definition document {
  relation viewer: user | user with in_region
  permission view = viewer
}
```

`user with in_region` requires the condition. `user | user with in_region`
allows either an ordinary share or a conditional one. Expiration-only shares
need the ordinary alternative. An unused Caveat definition does not require
an evaluator, but a relation that names one does, even before any shares exist.

A [wildcard](../README.md#wildcard-subjects) branch can require a Caveat too:
`relation anyone: user:* with nothing_sensitive` requires `nothing_sensitive`
on every `user:*` relationship, and EACL evaluates it for each user with that
request's context.

Names and parameter names use ASCII identifiers of up to 64 bytes. A Caveat
can have at most 32 parameters. CEL keywords, type names, and the `__eacl_`
prefix are reserved. Invalid definitions fail before replacing the schema.
SpiceDB accepts some Caveats outside these limits; EACL rejects those with
`:eacl.schema/unsupported-feature` (see
[SpiceDB schema compatibility](spicedb-schema-compatibility.md)).

A Caveat's expression is the CEL text SpiceDB compiles, and EACL stores it as
the Caveat's source: the body verbatim from its first token to its last. The
body ends at the `}` that closes it; braces nest, and braces in strings and
comments do not count. Whitespace and comments before the first token and
after the last are not part of the expression, whatever they contain. A
comment inside it is: CEL accepts `//` comments, and rejects `/* */`, so such
a body is `:eacl.caveat/invalid`. A line ending after a name or number, `)`,
`}` or `*` (not after a string literal) counts as a token here, as it ends a
statement elsewhere in a schema, so `x == 1 /* note */⏎}` keeps the comment
and is invalid, while `x == 1 /* note */ }` and `x == 1⏎/* note */⏎}` are
valid.

## Write and check a qualified Relationship

Write the schema above with `eacl/write-schema!`. This example uses `acl` and
the existing `alice` and `report` entities from the quickstart. `:touch`
updates the viewer relationship if it already exists:

```clojure
(def alice (eacl/spice-object :user "alice"))
(def report (eacl/spice-object :document "report"))
(def deadline-ms (+ (System/currentTimeMillis) 3600000))
(def grant (assoc (eacl/->Relationship alice :viewer report)
                  :caveat "in_region"
                  :caveat-context {"accepted" ["za"]}
                  :valid-until-ms deadline-ms)) ; exclusive UTC epoch milliseconds
(eacl/write-relationship! acl (assoc grant :operation :touch))

(eacl/check-permission acl
  {:subject        alice           :resource report :permission :view
   :caveat-context {"region" "za"}})
;; includes {:allowed? true :permissionship :has-permission}
```

Omitting `region` produces `:conditional-permission`, `:allowed? false`,
`:missing-fields ["region"]`, and a canonical `:residual`. Supply the missing
context in a new request. `can?` returns true only for a definite grant.
At `deadline-ms`, this grant is inactive without a database write or collector.
An expiring ban in `viewer - banned` can conversely turn denial into permission.

Each Relationship has at most one named Caveat. Identity is still subject,
Relation and resource; differing context or expiry does not create an independent
grant. `:create` conflicts with an existing identity even after expiry. Use
`:touch` to renew, shorten, change context, or remove qualification:

```clojure
(eacl/write-relationship! acl
  (assoc grant :operation :touch :valid-until-ms renewed-deadline-ms))
(eacl/write-relationship! acl
  {:operation :touch :subject alice :relation :viewer :resource report})
(eacl/delete-relationship! acl grant)
```

Batch updates use `{:operation :touch :relationship grant}` entries. Pass
`{:updates [...] :tx-data [...]}` to `write-relationships!` to commit application
datoms with the final publication. Identical intents coalesce; conflicting
qualifier values on one identity fail before allocation. Application datoms
cannot alter EACL's protected state.

For caller-managed native transaction composition, prepare each qualified value
with `prepare-relationship!`, acquire a snapshot **after** preparation, and pass
its opaque `:prepared-qualifier` handle alongside the corresponding update to
`tx-relationships`. Submit the returned native tx-data, then release the
snapshot. Preparation is inert; `discard-prepared-relationship!` removes an
unchanged, unattached preparation. The executable example shows this sequence.

## Public results and errors

Default lookups return definite `SpiceObject` results. `:result-policy :detailed`
returns `{:object ... :allowed? ... :permissionship ...}` items, including
conditional results and their missing fields/residuals. Detailed counts add
`:definite-count` and `:conditional-count`; they sum to `:count` and exclude
lookahead. A conditional interior edge may still compose into a definite result.

| Outcome or error | Meaning / caller action |
| --- | --- |
| `:has-permission` / `:no-permission` | Completed decision at the captured basis and time |
| `:conditional-permission` | Supply missing context in a new request; do not treat as a grant |
| `:eacl.caveat/invalid` | Invalid context, definition, profile, or resource bound; inspect the typed reason. `:reason :context-type` with `:parameter` rejects a context value that no reachable Caveat admits ([fail-fast admission](#fail-fast-context-admission)) |
| `:eacl/invalid-relationship-qualifier` | Invalid public Caveat/context/expiry input |
| `:eacl/relationship-conflict` | Identity already exists for create, or is absent for replace |
| `:eacl.caveat/evaluator-unavailable` | Install/supply a matching evaluator before serving the schema |
| `:eacl/unsupported-capability` | The backend/writer lacks the required certified operation |
| `:eacl.authorization/evaluation-failure` | The decision faults in some completion (see [Faults](#faults)); a lookup or count fails only on a consumed faulting candidate |
| `:eacl.schema/relationship-qualifier-in-use` | A schema change would invalidate retained Relationships, including expired ones |
| `:eacl.pagination/restart-required` | Live temporal certificate ended; begin a new lookup without the cursor |
| `:eacl.pagination/invalid-cursor` | Authentication, scope, or envelope mismatch; do not silently reuse its boundary |

`can?` converts authoritative qualified evaluation failures to false for Boolean
compatibility. Invalid requests, cancellation, execution limits and backend
errors still propagate. Detailed checks and collections expose faults; they
never erase a malformed subtracting edge into an absent ban.

## Faults

A fault is an edge whose qualification EACL cannot complete: a Caveat that
fails at runtime (for example a missing map key or a work limit), a malformed
or missing stored qualifier, or a Caveat that the Relation no longer admits.
EACL treats a fault as *unknown* and composes permissions with strong-Kleene
logic, separately in each completion of the remaining Caveat atoms:

| Operator | Decides alone | Otherwise faults when | Otherwise |
| --- | --- | --- | --- |
| union `a + b` | either side is true | either side faults | false |
| intersection `a & b`, arrow `a->b` | either side is false | either side faults | true |
| exclusion `a - b` | `a` is false or `b` is true | either side faults | true |

A definite grant beside a faulting branch is therefore a grant, and a definite
denial beside one is a denial. A faulting subtracted operand is never erased
into an absent ban: `viewer - banned` with a granted viewer and a faulting ban
faults. A faulting arrow edge counts only if its target can hold. A recursive
permission is the least fixed point in the order false < fault < true. A
subject holds a relation that declares `user:*` through its own relationship
or the wildcard's, so a definite grant through either one absorbs a fault
through the other.

The answer is a function of the schema, the relationships, the time and the
request context. It does not depend on the order in which EACL evaluates
operands, on schema text order, entity ids, the engine route, cache state or
page size. Evaluation never stops at a fault; it keeps looking for a definite
operand that absorbs it, which costs extra work only when a fault is present.

A decision that faults in some completion is an evaluation failure:
`check-permission` throws `:eacl.authorization/evaluation-failure` and `can?`
returns false. Lookups and counts fail only when a candidate they consume
faults. A page consumes candidates in order through its lookahead sentinel, an
exact count consumes every candidate, and a bounded count stops at the
lookahead result that proves truncation. A faulting edge that leads to no
resource never fails a walk. A relationship-filtered lookup composes each
candidate's decision with its filter edge before applying the result policy,
so `:definite` and `:detailed` fail on the same candidates. A `*` entry
excludes every subject with a relationship of its own whose decision is not a
definite grant, faulting subjects included; a faulting subject's own candidate
fails the walk only when a page consumes it. The error's `:faults` is a
non-exhaustive, implementation-defined sample of the faulting edges that the
decision depends on. It is deterministic for a given route, but another route
can report a different sample; the decision itself is canonical.

A fault that a definite answer absorbs does not vanish silently. The request
meters that a client's `:io-observer` receives include `:qualifier-faults`,
the qualified edges whose qualification faulted, and `:masked-faults`, the
compositions in which a definite operand absorbed a fault. A request that
succeeds with either meter positive had a fault that did not affect its
answer.

### Fail-fast context admission

Some faults are certain before evaluation. EACL first finds the Caveats a
request can reach: those that the Relations in the requested permission's
closure admit, on concrete and `T:*` wildcard branches alike, and, for a
relationship-filtered lookup, those that the filter's Relation admits. A
supplied context field that one of these Caveats declares is rejected when its
value fits none of the declared types, because every evaluation of such a
Caveat would fault on it:

```clojure
(eacl/check-permission acl
  {:subject alice :resource report :permission :view
   :caveat-context {"region" 5}})
;; throws :eacl.caveat/invalid
;; {:reason :context-type :parameter "region" :expected [:string]
;;  :caveats ["in_region"]}
```

`:expected` lists the declared types and `:caveats` the declaring Caveats.
When several fields are invalid, the first in parameter-name order is
reported. A field that no reachable Caveat declares is ignored. A value that
one reachable declaration admits is accepted even when another reachable
Caveat declares a different type; an edge whose Caveat rejects it faults only
if evaluation reaches it. In `check-permissions`, each check is admitted
before it is evaluated, in input order, and the error carries its
`:demand-index`.

Schema-independent context admission (map shape, parameter names, scalar and
container values, size bounds) still runs first and keeps its error shape.
Saved contexts are validated against their Caveat's declared parameters when
the relationship is written. Faults that only evaluation can detect, such as a
missing map key, an evaluation work limit or a stored qualifier that no longer
decodes, remain faults with the semantics above.

## Profile values and operations

EACL evaluates Caveats in EACL CEL profile 2: profile 1 plus the `exists` and
`all` macros. A stored definition records the lowest profile its expression
needs, so a Caveat without these macros stays profile 1.

| Type | Portable Clojure/CLJS value |
| --- | --- |
| `bool` | `true` or `false` |
| `int` | Exact integer from -9007199254740991 through 9007199254740991 |
| `string` | Valid Unicode text; no unpaired surrogates or normalization |
| `timestamp` | `[:timestamp epoch-ms]`, UTC years 0001 through 9999 |
| `list<T>` | Vector of one scalar type |
| `map<string,T>` | String-keyed map of one scalar type |

| Operation | Supported forms |
| --- | --- |
| Boolean logic | `!`, `&&`, `||` |
| Scalar equality | `==`, `!=` |
| Integer or timestamp comparison | `<`, `<=`, `>`, `>=` |
| List or map membership | `in` |
| String-keyed map access | `m[key]`, `m.member` |
| String matching | `contains`, `startsWith`, `endsWith` |
| List elements or map keys (profile 2) | `xs.exists(x, p)`, `xs.all(x, p)` |

Source literals are Boolean, exact integer, and JSON-style double-quoted
strings. Grouping and `//` comments are supported. Relational operators follow
CEL's shared precedence; use parentheses when mixing comparisons.

Unsupported forms include:

- Nested containers and container literals in source.
- Other macros (`has`, `exists_one`, `map`, `filter`), arithmetic, regex, and
  conditional expressions.
- Null, floats, unsigned integers, bytes, and durations.
- Conversions, list indexing, timestamp selectors, and string ordering or size.

Repeated ungrouped unary operators are rejected; `!(!a)` is supported.
These are explicit profile limits, not full CEL or SpiceDB compatibility.

| Bound | Maximum |
| --- | ---: |
| Source UTF-8 bytes / tokens / grouping depth | 8192 / 1024 / 32 |
| Plan nodes / depth | 256 / 32 |
| String UTF-8 bytes, including the key of an `m.member` access | 4096 |
| Entries in each list or map | 128 |
| Total context entries / canonical payload bytes | 1024 / 16384 |
| Conservative evaluation work units | 1048576 |
| Cached portable/native artifacts (shared capacity) / simultaneous builds | 256 / 4 |

Time values use exact epoch milliseconds from -62135596800000 through
253402300799999. Input, encoded payload and plan limits are checked before
expensive parsing or evaluation. Work preflight includes both logical branches;
an oversized branch is rejected even behind an absorbing `true` or `false`.
An absent parameter is charged at its declared maximum size: an absent
`list<string>` costs about half the work limit (128 entries of 4096 bytes).
Limits bound admitted work and retained programs, not wall-clock latency or
all JVM heap allocations.

### `exists` and `all`

`xs.exists(x, p)` is true when `p` holds for some element of the list `xs`,
and `xs.all(x, p)` when it holds for every element. On a map, they range over
its keys. This Caveat lets the caller say both what someone carries and what
is sensitive:

```zed
caveat nothing_sensitive(inventory list<string>, sensitive list<string>) {
  !inventory.exists(item, item in sensitive)
}
```

The variable is visible only inside `p`, where it hides a parameter or outer
variable of the same name. It must be a simple name other than `__result__`,
which SpiceDB uses for the macro's accumulator; for the same reason, a
parameter named `__result__` cannot be read inside a comprehension. Results
follow CEL and SpiceDB:

- A deciding element settles the result even when another element faults:
  `true` for `exists`, `false` for `all`.
- Otherwise a fault, such as a missing map key, is an error.
- Otherwise elements whose predicate needs a missing field make the result
  conditional on the fields they need. The residual keeps only those elements.
- Otherwise `exists` is false and `all` is true, as they are for an empty list.
- A missing list makes the result conditional on that list alone, as in
  SpiceDB: the predicate is not evaluated without elements.

When one element faults and another needs a missing field, EACL reports the
fault, as its `&&` and `||` do; SpiceDB reports the missing field. The
[SpiceDB fixture](../formal/fixtures/caveat-comprehensions/README.md) lists
every recorded difference.

A comprehension over a supplied list or map is charged for the range once,
then for one unit plus its predicate's work for every element, with the
variable as long as the longest element. Every element is charged, even when
an early one decides the result. Nested comprehensions multiply: an `exists`
over 128 elements inside another over 128 elements pays for 16,384
predicates. An absent range is charged at its declared maximum size and is not
iterated, since its result is conditional. An absent operand inside the
predicate is charged for every element, so `xs.exists(x, x in ys)` with
`ys` absent exceeds the limit once `xs` has two elements. Profile 2 adds no
limit: the 128-entry container bound and the work limit bound every
comprehension.

## Context, outcomes and implementation capability

Context maps use declared string keys. EACL validates both the saved and
request context, then lets saved values override request values. This lets a
share fix its accepted regions while the request supplies the current region.
A bad request value is still an error even if a saved value would override it.

Missing parameters can produce a conditional result. A missing key inside a
supplied map is an evaluation fault, not a request for more input. `can?`
returns true only for a definite grant. Use `check-permission` when your
application needs to distinguish denial, missing input, and evaluation errors.

The [JVM](../modules/eacl-caveats-jvm/README.md) and
[portable](../modules/eacl-caveats-portable/README.md) evaluator guides describe
the supported implementations. The portable evaluator passes the JVM module's
corpus and a differential test against it. Any other supplied evaluator must
implement the same bounded profile and pass the same conformance checks.

## Sparse qualifier storage and staged publication

A qualifier is the saved expiration or Caveat metadata attached to a
relationship. EACL stores it separately and updates both relationship
endpoints together. Applications should use the public relationship APIs
rather than editing qualifier entities directly.

`write-relationships!` handles allocation and publication for the backend.
An unattached value returned by `prepare-relationship!` grants no access until
published through `tx-relationships`. If you abandon a preparation, call
`discard-prepared-relationship!`.

## Integrity and cleanup

Expiration does not require a cleanup job. Expired relationships remain stored
until you delete them, and can be renewed with `:touch`.

Backend integrity namespaces provide `qualifier-report` and
`qualifier-proof-input` for offline inspection. Reports identify missing or
malformed metadata, shared ownership, and mismatched relationship halves.
Serving reads do not repair corruption automatically.

For abandoned preparations after a restart,
`eacl.relationships.qualifier-integrity/cleanup-orphans!` performs an offline
scan and removes one bounded batch. It refuses corrupt data or a database
that changed during the scan. The default batch is 100, with a maximum of
1,000. `cleanup-sweep!` can drain multiple batches on Datomic, DataScript, or
a direct Datahike writer. It is not a serving-path operation or an expiration
mechanism. Repeated single-batch calls may repeatedly scan the same data.

## Schema replacement and backend admission

Existing relationships, including expired ones, must remain valid under a new
permission schema. A change that removes a required Caveat or changes its
parameters incompatibly raises `:eacl.schema/relationship-qualifier-in-use`.
Delete or update the affected shares first. Unattached preparations can also
hold references to a Caveat.

Use a supported backend writer. Datomic and Datalevin allocate qualifiers in
the publication transaction; DataScript and direct Datahike writers use
prepared references. Unsupported configurations return
`:eacl/unsupported-capability`.

## Physical Relationship inspection

| `:relationship-state` | Rows returned |
| --- | --- |
| `:stored` (default) | Saved relationships, including expired ones. |
| `:expiry-active` | Rows whose deadline has not been reached at the request's evaluation time. |

Both modes return saved Caveat and expiration metadata. Neither mode evaluates
Caveats. A returned row is not proof of current access or permission to see
sharing metadata; use the [protected sharing recipe](#showing-saved-shares-and-current-access).

Filtering expired rows consumes the page's candidate budget. A page can be
empty and still have a continuation cursor; follow `:page-info` rather than
assuming that an empty page means there are no more rows.

`expand-permission-tree` lists stored Relationships the same way. A leaf
subject, or an arrow child node, reached through a qualified Relationship
carries its `:caveat`, `:caveat-context` (omitted when empty) and
`:valid-until-ms`. The tree never evaluates a Caveat or reads the clock, so it
shows expired and conditional Relationships next to plain ones; use `can?` or
`check-permission` for the decision.

## Decoded qualifier cache

Leave the qualifier cache at its default unless you need to tune it. Set
`:qualifier-cache {:max-entries 256}` to bound decoded entries, or
`:qualifier-cache false` to disable it. This caches decoded metadata, not
permission decisions. Expiration and Caveats are still evaluated for the
request. It is not included in exported authorization cache snapshots.

## Point-answer validity intervals

A cached time-dependent answer is reusable only within its recorded validity
interval. At its deadline, EACL recomputes even if the database has not changed.
An expired grant can remove access; an expired ban can restore it.

Qualified answers in the bundled backends use the exact database version and
request context. Lookup and count reuse also accounts for captured evaluation
time. See the [cache guide](cache.md).

## Trusted clocks and Peer skew

Each operation captures one server time for all its checks. EACL's default
clock does not move backwards within the process: after a backward clock
adjustment, it holds the last accepted time until the clock catches up.

This does not synchronize Peers or survive process restarts. Keep serving
clocks synchronized. A slow Peer can expire a grant late; a fast Peer can
expire it early. Zed tokens establish data visibility, not agreement on time.

Explicit snapshots keep their captured database version and time. Use a fresh
snapshot or the client for current access checks; a retained snapshot can
continue showing a past grant after its deadline.

## Qualified cursors

When continuing a lookup on a client, EACL checks the new server time against
the cursor's validity interval. If it has ended, EACL raises
`:eacl.pagination/restart-required`. Start again without `:after` or `:before`,
keeping the desired filters and context. A restarted result set can differ
because grants or bans expired.

A cursor on an explicit snapshot continues that snapshot's historical view.
Changing the Caveat context, result policy, or live-versus-snapshot mode
requires a new lookup. Cursor authentication and optional age limits still
apply independently.

## Qualified cache scope and reset traces

Cached qualified results depend on the database version, complete request
context, evaluator, and evaluation time or validity interval. Changing any of
these can require reevaluation. Optional decoded metadata caches do not bypass
those checks. After a restore or unsupported raw mutation, follow the
[cache recovery procedure](cache.md#coherence-and-recovery).

## Qualified object deletion

`delete-object!` removes relationships, including expired ones, and their
owned qualifier metadata. It does not delete the application entity or collect
unattached preparations. Use it before ordinary entity retraction, or use the
backend's safe-retraction helper; see [safe deletion](../README.md#deleting-a-secured-entity).

## Coordinated rollout and rollback

For retained databases:

1. Back up the database and complete the
   [relationship storage migration](relationship-storage-v7-to-v8.md).
2. Upgrade every serving Peer before allowing expiring or conditional writes.
3. Install the JVM or portable evaluator, or supply a compatible one, if
   relations use named Caveats. Expiration alone needs none.
4. Check clock health and ensure live authorization uses fresh evaluation time.
5. Exercise expiration without writes, missing-context results, and cursor
   restart before enabling the feature for users.

Older readers cannot safely interpret qualified data. Rolling back to them
requires stopping writes and restoring the compatible data and schema
checkpoint. Removing the evaluator alone does not make a rollback safe.

### Rolling out `exists` and `all`

Upgrade every serving Peer, core and evaluator module together, before writing
a schema whose Caveats use `exists` or `all`. Such a Caveat is stored as
profile 2. A Peer from an earlier release rejects that profile
(`:unsupported-profile`): its checks through the Caveat report an evaluation
fault, `can?` returns false, and it can neither read nor write the schema.
Caveats without the macros stay profile 1 and keep their stored form, so an
upgrade rewrites no definition and needs no coordination on its own.

Profile 2 changes the evaluator profile fingerprint, so an evaluator module
must be the same release as core; an older one is refused with
`:eacl.caveat/evaluator-unavailable`. Cached qualified answers are not reused
across the upgrade, and a cursor from a qualified lookup must be restarted
when a Peer on the other release continues it. To roll back, first write a
schema whose Caveats do not use `exists` or `all`; a Caveat keeps its
relationships when only its expression changes.

For verification details, see the [acceptance crosswalk](../formal/qualified/acceptance.md)
and [performance workloads](benchmarks/qualified-authorization.md).
