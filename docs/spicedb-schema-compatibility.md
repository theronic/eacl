# SpiceDB schema compatibility

EACL reads the schema language of SpiceDB v1.56.0. The rule is:

- **Every schema SpiceDB accepts** is accepted by EACL, or rejected with
  `:eacl.schema/unsupported-feature` naming the construct EACL cannot serve.
- **Every schema SpiceDB rejects** is rejected by EACL with a typed error
  (`:eacl.schema/parse-error`, `:eacl.schema/invalid-name`, ...).

EACL validates a schema as SpiceDB does before it applies its own
restrictions, so `:eacl.schema/unsupported-feature` means "valid SpiceDB, not
supported by EACL". The one exception is a caveat body that uses CEL EACL can
neither evaluate nor type-check (function calls, macros, map literals): EACL
cannot tell whether SpiceDB accepts it, and reports it as unsupported.

Two things are outside the rule: [resource limits](#resource-limits), and one
[deliberate difference](#deliberate-difference-transitive-wildcards), where
SpiceDB v1.56.0 accepts some schemas only because its transitive-wildcard
check is cached by relation name and EACL rejects them.

The rules below were derived from SpiceDB's source at tag `v1.56.0`
(`pkg/schemadsl`, `pkg/schema`, `internal/namespace`, `pkg/caveats` and
`proto/internal/core/v1/core.proto`) and confirmed against
`ghcr.io/authzed/spicedb:v1.56.0 serve-testing`. The compatibility corpus,
`modules/eacl/test/eacl/spicedb/fixtures/spicedb-1.56-corpus.edn`, holds 4,359
schemas with SpiceDB's verdict, and `eacl.spicedb.compatibility-corpus-test`
checks EACL against each of them on the JVM and in ClojureScript. For every
schema both accept, it also checks that EACL reads each relation and permission
exactly as SpiceDB's `ReadSchema` prints it.

## What EACL supports

| Construct | EACL |
| --- | --- |
| `;` and line-ending statement terminators, continuation lines, comments | Supported |
| `use expiration`, `with expiration`, `with cav and expiration` | Supported. EACL already permits expiring relationships on every relation; the trait is accepted and recorded (`:expiration?` on the parsed type), not enforced. `user` and `user with expiration` are one EACL branch. |
| `use partial`, `partial`, `...name` | Supported: partials are expanded where they are referenced |
| `use typechecking` and `permission p: user \| org = ...` | Supported: annotations are checked as SpiceDB checks them, then discarded |
| `rel.any(target)` | Supported (same as `rel->target`) |
| `rel.all(target)` | `:eacl.schema/unsupported-feature` |
| `nil`, `use self` with `self` | `:eacl.schema/unsupported-feature` |
| Wildcards `user:*`, `user:* with cav` | Supported ([wildcard subjects](../README.md#wildcard-subjects)) |
| Subject relations `group#member` | `:eacl.schema/unsupported-feature`; `user#...` is the plain type `user` |
| Prefixed names `org/user` | `:eacl.schema/unsupported-feature` (definitions and subject types; EACL types are simple keywords) |
| An arrow whose target some subject type lacks, or is a relation on some types and a permission on others | `:eacl.schema/unsupported-feature` |
| Recursion through an exclusion (`p = viewer - p`) | `:eacl.schema/unsupported-feature` |
| A definition, relation or permission named `self` | `:eacl.schema/unsupported-feature` (reserved by EACL's storage) |
| Caveats | Supported within EACL's CEL profile ([docs/caveats.md](caveats.md)). Other valid caveats are `:eacl.schema/unsupported-feature`: names with a `/` prefix, non-ASCII, longer than 64 bytes or reserved by the profile; parameter types `any`, `uint`, `double`, `bytes`, `duration`, `ipaddress`, nested containers, or type arguments on a basic type (`int<string>`); more than 32 parameters; and expressions outside the profile or its bounds (an issue with `:profile-reason :resource-limit` and the `:offset` in the expression). |
| Caveated relations without qualified admission (`{:allow-caveats? false}`) | `:eacl.schema/unsupported-feature` (unchanged) |

## Resource limits

Resource limits are outside the compatibility rule. A valid SpiceDB schema
that exceeds one of the limits below is rejected with
`:eacl.schema/expression-limit` (with its `:dimension`), or with
`:eacl.schema/parse-error` for nesting. (The bounds of EACL's CEL profile are
part of the profile: a Caveat beyond them is `:eacl.schema/unsupported-feature`,
as the table above says.) The configurable limits are the client's
`:expression-limits` ([README](../README.md#eacl-id-configuration)): each
default can be tightened or raised up to a portable hard ceiling.

- `:maximum-schema-source-bytes` (1,048,576 by default, also the ceiling)
  bounds the schema text in UTF-8 bytes (`:dimension :source-bytes`) and the
  two validation bounds below.
- The per-permission and aggregate expression limits bound each permission
  and the schema's permissions together, for example a permission deeper
  than `:maximum-source-depth` (64 levels by default).
- The parser stops at 256 nested parentheses with `:eacl.schema/parse-error
  {:reason :nesting-depth}`. This limit is fixed.
- Partials expand by copying: `partial b { ...a ...a }` doubles `a`, and a
  few lines of such partials stand for billions of members (SpiceDB itself
  copies them all). Translating partials and expanding definitions visit at
  most a quarter of `:maximum-schema-source-bytes` statements (262,144 by
  default), more than a schema of that size holds written out without
  partials; beyond that the schema is `:eacl.schema/expression-limit
  {:dimension :partial-expansion}`. Unused partials are never expanded.
- Under `use typechecking`, each annotation walks everything its permission
  reaches, as SpiceDB's check does; the walks visit at most
  `:maximum-schema-source-bytes` relations and permissions in total, beyond
  which the schema is `:eacl.schema/expression-limit {:dimension
  :typechecking}`.

Both validation bounds carry `:maximum`, `:actual-at-least` and
`:limit :maximum-schema-source-bytes`, and scale with that limit.

Validation is linear in the schema with its partials expanded, and the limits
that bound it come first. EACL's source limits (each relation's subject-type
count, each permission's source nodes, depth and fan-in, in the resolver's
order) are checked before SpiceDB's reference checks, so a permission that
exceeds them reports `:eacl.schema/expression-limit` rather than one issue per
leaf.

## Errors

| Error | Raised for |
| --- | --- |
| `:eacl.schema/parse-error` | Lexical and syntax errors. `:reason` is `:syntax`, `:invalid-character`, `:unterminated-string`, `:unterminated-comment`, `:nested-arrow`, `:arrow-function`, `:expiration-order`, `:unknown-use-flag`, `:use-after-definition`, `:import-not-allowed`, `:missing-caveat-expression` or `:nesting-depth`; `:line`, `:column`, `:expected` and `:found` locate it. |
| `:eacl.schema/invalid-name` | A name breaks SpiceDB's name rules (`:kind`, `:name`, `:rule`) |
| `:eacl.schema/duplicate-definition`, `-caveat`, `-relation`, `-permission`, `:eacl.schema/name-collision` | Reused names, including a caveat named like a definition |
| `:eacl.schema/duplicate-relation-branch` | A subject type repeated in one relation (`user \| user`, `user \| user#...`) |
| `:eacl.schema/expression-resolution-failed` | Missing references, an arrow over a permission, and the wildcard rules (`:errors` lists each issue) |
| `:eacl.schema/invalid-caveat-reference` | `with cav` names no caveat |
| `:eacl.schema/invalid-partial` | An undefined or circular partial, or a partial named like a definition or caveat |
| `:eacl.schema/incomplete-type-annotation` | With `use typechecking`, a reachable subject type missing from an annotation |
| `:eacl.schema/permission-alias-cycle` | Permissions that only alias each other in a cycle (`a = b`, `b = a`, or `p = p`) |
| `:eacl.caveat/invalid` | An invalid caveat: unknown type, wrong type arity, duplicate or unreferenceable parameter, CEL syntax or type error, non-`bool` result, unused parameter |
| `:eacl.schema/expression-limit` | A [resource limit](#resource-limits), including `:dimension :partial-expansion` and `:dimension :typechecking` |

## Lexing

| Token | Rule |
| --- | --- |
| whitespace | U+0020 and U+0009 only. Form feed, vertical tab, NBSP, U+0085, U+2028, a BOM and every other character are *unrecognized characters* outside comments and strings. |
| newline | `\n` or `\r`; `\r\n` is two newlines |
| comments | `//` to the end of the line; `/* ... */`, not nested. An unclosed `/*` is an error. |
| word | A maximal run of `_`, Unicode letters and Unicode decimal digits; digits may lead (`1abc` is one word). Letters and digits are those of Unicode 15.0.0, SpiceDB v1.56.0's Go tables: a letter added in Unicode 16.0 is an unrecognized character, whatever the host's Unicode version (`eacl.spicedb.unicode`). `definition caveat relation permission nil with` are keywords; every other word is an identifier. There are no number tokens. |
| string | `"..."` or `'...'` on one line, `"""..."""` across lines. A `'''` string closes only at `"""`. There are no escapes: `"a\"b"` ends after the backslash. |
| punctuation | `{ } ( ) [ ] + - & \| / = : ; # * , . ? ! % < >`, and `->`, `...`, `\|\|`, `&&`, `==`, `!=`, `<=`, `>=` |

Any lexical error rejects the schema, including one inside a caveat body.

**Statement termination.** A newline is a terminator when the previous
significant token (not whitespace, not a comment) is an identifier, a keyword,
`)`, `}` or `*`. After any other token, and after a terminator, it is
whitespace. A `/* */` comment that spans lines never terminates. So a
statement continues onto the next line only after an operator or opener
(`= + & - -> | : ( { , . / # <`, or `...`):

- `definition doc⏎{` and `caveat c(x int)⏎{` are rejected;
- `relation r: user with⏎ c` is rejected;
- `relation r: user#...⏎}` is rejected (nothing ends the statement after `...`), while `user#...;` is accepted;
- inside parentheses, a newline after an identifier still terminates: `(a⏎+ b)` is rejected.

**`use` flags.** Before the first `definition` or `caveat`, the identifier
`use` followed by `expiration`, `self`, `typechecking`, `partial` or `import`
enables that flag. From then on these words are keywords: `expiration` and
`and` (flag `expiration`), `self`, `typechecking`, `partial` and `import`.
Without the flag each is an ordinary identifier, so `relation self: user` and
`caveat expiration(...)` are valid.

## Grammar

`T` is a terminator: `;` or a terminating newline.

```text
schema      = { top-level } EOF                 (one optional T before each item)
top-level   = use | definition | caveat | partial | import
use         = "use" flag
definition  = "definition" type-path body
partial     = "partial" type-path body           (keyword only with `use partial`)
import      = "import" string                    (keyword only with `use import`)
body        = "{" { [ relation | permission | "..." identifier ] ( T | EOF ) } "}"
relation    = "relation" identifier ":" type-ref { "|" type-ref }
type-ref    = type-path [ ":" "*" | "#" ( identifier | "..." ) ] [ traits ]
traits      = "with" ( "expiration" | type-path [ "and" [ "expiration" ] ] )
type-path   = identifier { "/" identifier }
permission  = "permission" identifier [ ":" identifier { "|" identifier } ] "=" expr
expr        = intersect { "-" intersect }
intersect   = union { "&" union }
union       = arrow { "+" arrow }
arrow       = identifier [ "->" identifier | "." identifier "(" identifier ")" ]
            | "(" expr ")" | "nil" | "self"
caveat      = "caveat" type-path "(" param { "," param } ")" "{" caveat-body "}"
param       = identifier type
type        = identifier [ "<" type { "," type } ">" ]
caveat-body = every token up to the matching "}"; the CEL expression is the text
              from its first token to its last
```

- Top-level items need no separator (`definition a {} definition b {}`), but
  a second terminator is not an item: `definition a {};;` and a line holding
  only `;` are rejected; a leading `;` is accepted.
- In a body, empty statements are allowed (`definition d { ; }`), and the last
  member must still be terminated before `}`: `{ relation r: user }` on one
  line is rejected.
- `-` binds loosest, then `&`, then `+` (`a - b + c` is `a - (b + c)`;
  `a & b - c` is `(a & b) - c`). All three are left-associative.
- An arrow starts at an identifier: `(a)->b`, `nil->b` and `a->(b)` are
  rejected. The function must be `any` or `all`. A chained arrow (`a->b->c`,
  `a->b.any(c)`) is rejected.
- `with cav and expiration` is valid; `with expiration and cav` and
  `with cav and expiration and` are rejected; `with cav and` followed by a
  terminator is accepted (the `and` is ignored). Without `use expiration`,
  `with expiration` names a caveat called `expiration`.
- `use` must precede every `definition` and `caveat`, and the flag must be
  known. `WriteSchema` rejects every `import`.
- A partial reference names one identifier (`...ns/name` is rejected).
  Partials expand recursively; an undefined or circular partial is rejected,
  and of two partials with one name the later wins. Partials are translated
  in source order; one that references a partial not yet translated waits for
  it and is translated again once it is, seeing any partial redefined
  meanwhile. Partial names are not otherwise checked.
- Type annotations parse with or without `use typechecking`. Without the flag
  they are discarded; with it, every type reachable from the permission
  (through relations, subject relations, wildcards and arrows) must be listed.
  Annotated names need not exist.

## Names

| Position | Rule |
| --- | --- |
| definition | `^([a-z][a-z0-9_]{1,62}[a-z0-9]/)*[a-z][a-z0-9_]{1,62}[a-z0-9]$`, at most 128 bytes |
| subject type | as a definition, but each prefix segment is at most 63 characters |
| relation, permission, `#relation`, both sides of an arrow, referenced names | `^[a-z][a-z0-9_]{1,62}[a-z0-9]$` (3 to 64 characters) |
| caveat | any identifier path (Unicode, leading digits, any length) |
| caveat parameter | a CEL identifier that is not a CEL reserved word (`in as break const continue else for function if import let loop package namespace return var void while true false null`) or type identifier (`bool int uint double string bytes list map type null_type`) |
| partial, type annotation | any identifier |

A keyword cannot be a name (`definition definition {}`). Definitions and
caveats share one namespace.

## Validation

- Relation and permission names are unique within a definition, after
  partial expansion. Of several repeated names EACL reports the first, in
  order of first occurrence; of several reference problems, all of them,
  sorted.
- A referenced name (`view = viewer`) must be a relation or permission of the
  same definition.
- The left side of an arrow must be a relation of the definition; not a
  permission, and not one that reaches a wildcard through its subject types
  (directly or through subject relations). The right side is not checked.
- A subject type must be a definition. A subject relation must be a relation
  or permission of its definition and, on another definition, must not reach a
  wildcard.
- Subject types are compared by their source text (`user`, `user#...`,
  `user with cav`, `user with cav and expiration`, `user:*`): an exact repeat
  is rejected; `user | user with cav` is not a repeat.
- A referenced caveat must exist.
- A permission whose expression is one name (`p = q`, `p = (q)`) is an alias;
  a cycle of aliases is rejected. Every other recursion is valid.
- Caveats need at least one parameter, no duplicate parameters, known types
  (`any bool bytes double duration int ipaddress string timestamp uint`,
  `list<T>`, `map<T>`), a body that compiles as CEL with a `bool` result, every
  parameter referenced, and at most 100,000 characters of expression.

A caveat's expression is the body text from its first token to its last
(tokens include a line ending that terminates, so `x == 1 /* c */⏎}` passes the
comment to CEL, which rejects it). Comments and whitespace outside that span
are dropped; `//` comments inside it are valid CEL and `/* */` comments are
not. EACL stores exactly this text as the caveat's expression source.

## The corpus

The corpus has 4,359 schemas, 1,746 accepted and 2,613 rejected by SpiceDB:
1,355 written by hand in sections that follow the rules above (`lexer`,
`unicode-version`, `comments`, `terminators`, `names*`, `expressions`, `arrows`, `use`,
`partials*`, `typechecking`, `cycles`, `caveat-*`, `cel*`, `validation`, and
`eacl-rs` for the eacl-rust port's findings), and 3,004 generated (`gen/`):
deterministic token mutations of valid schemas (line breaks inserted, removed
or replaced, `\r\n` and `\r` line endings, `;` inserted, tokens deleted,
doubled or swapped, comments inserted, whitespace removed), random
expressions, and random token sequences. Each schema was
written twice, each time to a fresh tenant (`serve-testing` keys data by
bearer token), and a schema whose verdict differed between writes is not in
the corpus. Every entry records SpiceDB's verdict, the first line of its error
message for a rejection, and its `ReadSchema` text for an acceptance.

The generator lives in the eacl-rust repository under `tools/spicedb-corpus`
(Python 3, standard library only). With the SpiceDB container listening on
`localhost:18443`, `build_corpus.py corpus.json` writes the raw results and
`emit_edn.py corpus.json spicedb-1.56-corpus.edn` writes this fixture.

## Deliberate difference: transitive wildcards

SpiceDB rejects a subject relation that reaches a wildcard
(`relation ggg: group#member` where `group`'s `member` is `user:*`), but
v1.56.0 caches that check by relation name alone, across definitions. When an
earlier relation with the same name has no wildcard, a later `group#member`
that reaches `user:*` is accepted; within one definition the result depends on
Go map order, so one schema can be accepted and rejected on successive writes.

EACL deliberately keeps the check per definition and relation, and rejects
these schemas with `:eacl.schema/expression-resolution-failed` and a
`:transitive-wildcard` issue. Apart from resource limits, this is the one
place where EACL rejects a schema SpiceDB accepts with an error other than
`:eacl.schema/unsupported-feature`. The corpus keeps three reproductions under
`:spicedb-defects`, which the corpus test expects EACL to reject, and excludes
every schema whose verdict varied across repeated writes.
