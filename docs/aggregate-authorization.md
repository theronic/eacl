# Aggregate authorization

Use `check-permissions` to check several permissions against one snapshot.
Use a filtered relationship read or lookup when a page needs both a permission
check and a direct relationship filter.

These examples describe `8.0.0-RC-2026-09-12`. The
[consumer checks](examples/datomic-consumer/) exercise both page routes.

## Ordered point-check batches

```clojure
(defn document-actions [acl user document]
  (eacl/check-permissions acl
    {:checks     [{:subject user :permission :view :resource document}
                  {:subject user :permission :edit :resource document}]
     :timeout-ms 5000}))
```

The result is a vector in the same order as `:checks`, including duplicates.
Each item has the usual `check-permission` result, including `:allowed?`.
All checks share one snapshot and deadline. An invalid request, timeout,
cancellation, backend failure, or limit failure rejects the batch; EACL does
not return a partial vector.

## Authorized relationship pages

Suppose documents have a `:folder` relation and a `:view` permission. These
functions list documents in a folder that a user may view. The scan route
returns relationships; the lookup route returns resource objects.

```clojure
(defn visible-folder-relationships [acl user folder]
  (eacl/read-relationships acl
    {:subject/type      :folder
     :subject/id        (:id folder)
     :resource/type     :document
     :resource/relation :folder
     :authorization     {:subject user :permission :view :on :resource}
     :first             50
     :aggregate-limits  {:candidate-window 500}}))
```

The scan first finds matching relationships and checks `:view` on each resource.
Set `:on :subject` when the permission should be checked on the other endpoint.

```clojure
(defn visible-folder-documents [acl user folder]
  (eacl/lookup-resources acl
    {:subject               user
     :permission            :view
     :resource/type         :document
     :resource/relationship {:relation :folder :subject folder}
     :first                 50
     :aggregate-limits      {:candidate-window 500}}))
```

The lookup first finds authorized resources, then checks for the direct folder
relationship. For `lookup-subjects`, the corresponding option is
`:subject/relationship {:relation relation :resource anchor}`.
The unprefixed lookup option `:relationship` is not supported.

| Route | Starts with | Use when |
| --- | --- | --- |
| Relationship scan | Relationships matching the filters | The folder has relatively few documents. |
| Resource lookup | Resources the user may access | The user can access relatively few documents. |

Choose based on your application's data. Both routes enforce the requested
permission, but they return different kinds of values.

## Protecting sharing metadata

Being allowed to view a document need not mean being allowed to see its sharing
list. Check the application's sharing-management permission before returning
saved relationships. Use the same snapshot for the check and read; see
[the complete sharing-read recipe](caveats.md#showing-saved-shares-and-current-access).
Use the authenticated caller as the subject, not an arbitrary user ID supplied
by the browser.

## Candidate windows and short pages

`:candidate-window` limits how many candidates EACL examines for one page.
A page can therefore contain fewer than `:first` results, or none, while still
having more work to do.

| Page field | Meaning |
| --- | --- |
| `:bounded? true` | EACL stopped at the candidate window before proving exhaustion. |
| `:has-next-page? true` | Continue using `:end-cursor`; a bounded page may have no accepted rows. |
| `:bounded? false` | `:has-next-page?` reports whether another result exists. |

Continue with the same query and the returned cursor. A timeout or failure
returns an error, not a partial page or resumable cursor.

## Cursor confidentiality, scope, and cache provenance

Cursors are encrypted and authenticated. They belong to one query, route,
page size, and set of limits. Changing those inputs requires a new lookup.
If the selected database version cannot be recovered, EACL returns an error
instead of silently restarting at a different version.

Results include `:cached?` and `:cache-basis`. Cached results follow the same
permission and pagination rules; see [cache behavior](cache.md) and
[security keys](security-keyrings.md).
