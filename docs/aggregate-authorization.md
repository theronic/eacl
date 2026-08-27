# Aggregate authorization

EACL provides two aggregate read families that keep one immutable snapshot,
one absolute deadline, one cancellation token, and one request-local memo for
the complete operation. They do not loop through the public scalar API.

## Ordered point-check batches

Use `check-permissions` when one request needs several point decisions:

```clojure
(eacl/check-permissions
 acl
 {:checks [{:subject alice :permission :view :resource document-1}
           {:subject alice :permission :edit :resource document-1}
           {:subject bob   :permission :view :resource document-2}]
  :cache? true
  :timeout-ms 5000
  :aggregate-limits {:max-batch-size 1000}})
;; => [{:allowed? true  :cached? false :cache-basis ...}
;;     {:allowed? false :cached? false :cache-basis ...}
;;     {:allowed? true  :cached? true  :cache-basis ...}]
```

The result has the same order and cardinality as `:checks`, including duplicate
positions. Each decision has the ordinary `check-permission` result shape and
reports the cache artifact actually used. Request-local sharing is not called a
durable cache hit. The request envelope is closed; controls such as
`:consistency`, `:evaluation`, `:timeout-ms`, `:cancellation-token`, `:cache?`,
and `:aggregate-limits` are request-wide. Any invalid demand, deadline,
cancellation, backend failure, or aggregate-limit failure rejects the whole
batch and identifies the failing `:demand-index`; no partial vector is returned.

## Authorized relationship pages

There are two explicit routes over the same logical filter. The caller chooses
which candidate set is smaller.

The scan route reads matching relationships and authorizes one endpoint of each
candidate:

```clojure
(eacl/read-relationships
 acl
 {:subject/type :account
  :subject/id "account-1"
  :resource/type :document
  :resource/relation :account
  :authorization {:subject alice
                  :permission :view
                  :on :resource}
  :first 50
  :aggregate-limits {:candidate-window 500}})
```

The enumerate route first discovers authorized objects and performs one
certified direct-relationship membership probe for each candidate:

```clojure
(eacl/lookup-resources
 acl
 {:subject alice
  :permission :view
  :resource/type :document
  :resource/relationship {:relation :account
                          :subject account-1}
  :first 50
  :aggregate-limits {:candidate-window 500}})
```

The reverse shape is
`lookup-subjects` with
`:subject/relationship {:relation relation :resource anchor}`. Types,
permissions, and direct relations are schema-validated before traversal.

| Route | Candidate stream | Per-candidate work | Approximate selection cost | Prefer when |
| --- | --- | --- | --- | --- |
| Scan | Relationships matching the ordinary filters | One context-bound permission decision on `:subject` or `:resource` | matching relationships × authorization cost | The relationship set is small |
| Enumerate | Objects authorized by the lookup | One certified direct-match probe | authorized objects × membership-probe cost | The authorized set is small |

Neither route dominates. For example, enumerate is usually best for one user's
few visible documents; scan is usually best when a super-admin filters a few
relationships. EACL deliberately does not choose adaptively because the caller
knows which side is bounded.

## Candidate windows and short pages

`:aggregate-limits {:candidate-window W}` bounds candidates examined for one
page. A page stops at physical exhaustion, the `N+1` accepted sentinel for an
`N`-row request, or the window boundary. Reaching the window is normal progress,
not a denial or resource error. The response can therefore contain fewer than
`N` rows with both `:has-next-page? true` and `:bounded? true`; continue with its
`:end-cursor`. When `:bounded?` is false, `:has-next-page?` is exact.

```clojure
{:data [accepted-rows ...]
 :page-info {:start-cursor nil
             :end-cursor "eacl_c5_..."
             :has-next-page? true
             :has-previous-page? false
             :bounded? true}
 :cached? false
 :cache-basis ...}
```

Deadlines and candidate windows are different controls. A deadline,
cancellation, backend, rendering, or publication failure returns no rows and no
cursor. Nested work consumes the original absolute deadline and cumulative
aggregate limits; it never renews or resets them.

## Cursor confidentiality, scope, and cache provenance

Aggregate cursors use EACL's authenticated-encryption envelope. The progress
anchor is confidential. The cursor binds the route, operation, ordinary query,
authorization or relationship clause, direction, page demand, window budget,
source/lifecycle/basis identity, certified schema generation, dependency proof,
and ordering ABI. A cursor cannot cross from scan to enumerate or be reused
with a different subject, permission, endpoint, relation, anchor, filter,
direction, page size, or answer-affecting limit. If its exact basis or proof is
no longer available, continuation fails closed instead of restarting.

Successful aggregate results retain the ordinary `:cached?` and `:cache-basis`
fields. An exact identical-basis page hit is reported as a hit. A proof-backed
hit is possible only when the adapter certifies the complete ordered-generation
frontier. Datalevin intentionally has no such proof capability, so it reuses
completed answers only at the identical selected revision. Every bundled
backend independently certifies `:schema-generation`, allowing parsed schema,
validation catalogs, dependency closures, and sealed plans to survive
relationship-only revisions even when completed answers cannot.

## Performance qualification

The checked-in gates compare aggregate routes with a scalar-loop oracle and
enforce deterministic acquisition, release, sealing, candidate, and probe
counters. Absolute ceilings apply only to their recorded host class. HTTP
reports isolate the local framework share with a no-op control and are not
ratio-gated. These measurements demonstrate removal of request amplification;
they do not establish a universal or portable sub-millisecond service-level
agreement.
