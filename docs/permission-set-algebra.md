# Permission set algebra

Combine relations and named permissions to describe who can do what:

| Expression | Meaning |
| --- | --- |
| `a + b` | Anyone in `a` or `b` (union). |
| `a & b` | Anyone in both `a` and `b` (intersection). |
| `a - b` | Anyone in `a` who is not in `b` (exclusion). |
| `folder->view` | Follow the `folder` relation and use that folder's `view` permission. |

## Syntax and denotation

```zed
definition user {}

definition document {
  relation reader: user
  relation writer: user
  relation banned: user

  permission contributor = reader & writer
  permission view = (reader + writer) - banned
}
```

A contributor must be both a reader and a writer. A reader or writer may view
the document unless they are banned. Checks, lookups, and counts apply the
same permission rules. A permission tree shows their structure; it is not an
authorization decision.

Operator precedence is:

1. Parentheses.
2. Union (`+`).
3. Intersection (`&`).
4. Exclusion (`-`), evaluated from left to right.

For example, `a + b & c - d` means `((a + b) & c) - d`, and `a - b - c`
means `(a - b) - c`. Use parentheses when the grouping might surprise a reader.

## Recursion and exclusion

Permissions can refer to other permissions recursively. A positive cycle
alone grants nothing; there must be a relationship that provides a grant.

A cycle cannot depend on its own exclusion result. EACL rejects such a schema
with `:eacl.schema/unstratified-exclusion` and keeps the previous schema.
For example, `permission view = reader - view` is invalid.

An intersection or exclusion whose operands recurse only through unions costs
about what its operands cost:

```zed
permission read_account = reader + parent->read_account
permission delete_granted = deleter + parent->delete_granted
permission delete = delete_granted & read_account
```

EACL decides each operand the way it decides that operand on its own, and it
generates `delete`'s candidates with the traversal a lookup of one operand
uses. This holds when relationships expire, too. An operand whose only
witnesses expire is decided in a few passes, one per distinct expiry it
meets, and the grant is certified until the last of those witnesses
expires.

Caveated relationships are still decided one resource at a time. So is any
evidence that could let access appear later.

The same holds when the operator sits under a union, or when a union is an
intersection's anchor:

```zed
permission delete_top = deleter + (delete_granted & read_account)
```

EACL generates `delete_top`'s candidates from `deleter` and from
`delete_granted`'s own traversal. It decides `deleter` for a whole page from
the subject's `deleter` grants, read once per request.

A permission that recurses through the operator itself, such as
`view = reader + (parent->view & eligible)`, needs stratified recursive
evaluation, which costs more per result.

EACL supports one-hop arrows. A target permission can contain another arrow,
but a directly chained expression such as `a->b->c` is not supported.

Other unsupported forms are:

- `.all()` intersection arrows.
- Wildcard subjects and `subject#relation` subject sets.
- `nil` and `self` permission operands.

[Caveats and expiration](caveats.md) can qualify relationships used by these
expressions. An expired ban can restore access, just as an expired grant can
remove it.

## Order, pagination, and cursors

Results have a stable order for one query and cursor walk. EACL does not
promise alphabetical order or the same order across backends. Sort by an
application field when presentation order matters.

Continue with the returned cursor and the same query options. Changing the
permission, subject, filters, or page size requires a new lookup. EACL rejects
an incompatible cursor instead of moving its boundary to another result set.

## Cache behavior

An exclusion depends on both sides, including an empty banned set. Adding a
ban through EACL therefore invalidates a cached grant that depended on its
absence. Time-dependent results also stop being reusable at their deadline.

A timeout or incomplete traversal is not cached as a denial. Pass
`:cache? false` to bypass shared authorization-result caching for a request.
See the [cache guide](cache.md).

## Limits

EACL rejects expressions that exceed its configured limits before saving the
schema. The default limits include:

| Limit | Per permission | Whole schema |
| --- | ---: | ---: |
| Source nodes | 512 | 16,384 |
| Normalized nodes | 512 | 16,384 |
| Child slots | 1,024 | 32,768 |
| Encoded expression bytes | 131,072 | 16,777,216 |
| Internal expression words | 1,024 | 32,768 |
| Checkpoint weight | 131,072 | 8,388,608 |

Other defaults are:

- Schema source: 1,048,576 bytes.
- Permission count: 1,024.
- Expression depth per permission: 64.
- Direct operands per permission: 128.
- Type partitions per permission: 256.

Runtime traversal also has work limits. Exceeding one raises an error rather
than returning a partial authorization answer. Use `:count-limit` when you
only need to know whether a count reaches a threshold. See
[recursive controls](v8-backend-modules-and-upgrade.md#recursive-permissions-and-safety-controls).

## Performance verification

For benchmark workloads and reproduction commands, see the
[operator benchmark guide](benchmarks/operator-engine.md).
