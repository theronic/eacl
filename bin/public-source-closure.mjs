#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(
  process.env.EACL_REPO_ROOT ??
    resolve(dirname(fileURLToPath(import.meta.url)), ".."),
);
const sourcePaths = [
  "modules/eacl/src",
  "modules/eacl-caveats-jvm/src",
  "modules/eacl-datomic/src",
  "modules/eacl-datahike/src",
  "modules/eacl-datascript/src",
  "modules/eacl-datalevin/src",
];
const reportPath =
  process.env.EACL_PUBLIC_SOURCE_CLOSURE_OUTPUT ??
  "target/formal/verification/public-source-closure.json";
const roots = [
  "eacl.caveats.evaluator/evaluate",
  "eacl.caveats.evaluator/require-matching!",
  "eacl.caveats.jvm/evaluator",
  "eacl.caveats.jvm/evaluate-definition",
  "eacl.relationships.qualifier-integrity/proof-input",
  "eacl.relationships.qualifier-integrity/report",
  "eacl.relationships.qualifier-integrity/cleanup-orphans!",
  "eacl.relationships.staged/prepare!",
  "eacl.relationships.staged/plan-current",
  "eacl.relationships.staged/write!",
  "eacl.relationships.staged/cleanup!",
  "eacl.caveats.plan/compile-plan",
  "eacl.caveats.plan/decode-plan",
  "eacl.caveats.partial/evaluate",
  "eacl.datomic.qualifiers/writer",
  "eacl.datascript.qualifiers/writer",
  "eacl.datahike.qualifiers/writer",
  "eacl.datalevin.qualifiers/writer",
  "eacl.engine.v8/can?",
  "eacl.engine.v8/lookup-resources",
  "eacl.engine.v8/lookup-subjects",
  "eacl.engine.v8/count-resources",
  "eacl.engine.v8/count-subjects",
  "eacl.engine.relationships/execute-page",
  "eacl.engine.relationships/execute-plan",
  "eacl.relay/select-continuation-adapter",
  "eacl.relay/prepare-page-query",
  "eacl.relay/internalize-page-query",
  "eacl.relay/externalize-page",
  "eacl.relay/externalize-relationship-page",
  "eacl.cursor/cursor->token",
  "eacl.cursor/token->cursor",
  "eacl.cache/resolve-basis!",
  "eacl.cache/resolve-managed-read-only!",
  "eacl.cache/export-basis-snapshot",
  "eacl.cache/restore-basis-snapshot!",
  "eacl.cache/cache-content-revision",
  "eacl.subproblem-cache/lookup!",
  "eacl.subproblem-cache/lookup-eligible!",
  "eacl.subproblem-cache/publish!",
  "eacl.subproblem-cache/lookup-denotation!",
  "eacl.subproblem-cache/publish-denotation!",
  "eacl.subproblem-cache/export-snapshot",
  "eacl.subproblem-cache/restore-store",
  "eacl.consistency/select",
  "eacl.consistency/cursor-conflict!",
  "eacl.causal-token/issue",
  "eacl.causal-token/token-data",
  "eacl.formal.production-kernel/GeneratedJavaKernel",
  "eacl.formal.production-kernel-cljs/default-selection",
  "eacl.engine.portable-decisions/PortableDecisionKernel",
  "eacl.engine.portable-indexed/PortableIndexedKernel",
  "eacl.core/check-permission",
  "eacl.core/can?",
  "eacl.core/read-schema",
  "eacl.core/read-relationships",
  "eacl.core/lookup-resources",
  "eacl.core/count-resources",
  "eacl.core/lookup-subjects",
  "eacl.core/count-subjects",
  "eacl.core/expand-permission-tree",
  "eacl.core/check-permissions",
  "eacl.core/write-schema!",
  "eacl.core/write-relationships!",
  "eacl.core/delete-object!",
  "eacl.core/write-relationship!",
  "eacl.core/create-relationships!",
  "eacl.core/create-relationship!",
  "eacl.core/delete-relationships!",
  "eacl.core/delete-relationship!",
  "eacl.core/snapshot",
  "eacl.core/release!",
  "eacl.core/released?",
  "eacl.core/basis",
  "eacl.core/basis-token",
  "eacl.core/with",
  "eacl.core/with-schema",
  "eacl.core/tx-relationship",
  "eacl.core/speculative-diagnostics",
  "eacl.client.orchestration/Acl",
  "eacl.client.orchestration/Snapshot",
  "eacl.client.orchestration/speculative-with-snapshot",
  "eacl.client.orchestration/speculative-with-schema-snapshot",
  "eacl.client.orchestration/export-cache-snapshot",
  "eacl.client.orchestration/restore-cache-snapshot!",
  "eacl.client.orchestration/cache-content-revision",
  "eacl.client.orchestration/make-client",
  "eacl.datomic.core/make-client",
  "eacl.datomic.core/db",
  "eacl.datomic.core/export-cache-snapshot",
  "eacl.datomic.core/restore-cache-snapshot!",
  "eacl.datomic.core/cache-content-revision",
  "eacl.datomic.core/current-zed-token",
  "eacl.datomic.core/basis-instant",
  "eacl.migrations.v7-to-v8/migrate!",
  "eacl.datahike.core/make-client",
  "eacl.datahike.core/db",
  "eacl.datahike.core/export-cache-snapshot",
  "eacl.datahike.core/restore-cache-snapshot!",
  "eacl.datahike.io/storage-io-stats-available?",
  "eacl.datahike.io/call-with-storage-io-stats",
  "eacl.datahike.io/with-storage-io-stats",
  "eacl.datahike.core/cache-content-revision",
  "eacl.datahike.migrations.v7-to-v8/migrate!",
  "eacl.datahike.migrations.v7-to-v8/stamped-permission-storage-version",
  "eacl.datahike.schema/migrate-v7-permissions!",
  "eacl.datahike.schema/permission-storage-shape",
  "eacl.datascript.core/make-client",
  "eacl.datascript.core/db",
  "eacl.datascript.core/export-cache-snapshot",
  "eacl.datascript.core/restore-cache-snapshot!",
  "eacl.datascript.core/cache-content-revision",
  "eacl.datalevin.core/make-client",
  "eacl.datalevin.core/db",
];
const ignoredRuntimeNamespaces = new Set(["clojure.core", "cljs.core"]);
const forbiddenPolicyTokens = [
  "current-cache-decision",
  "subproblem-cache-decision",
  "current-cache-refinement",
  "specialized-current-cache-action",
  "current-cache-specialization",
  "PageNavigationCache",
  "page-navigation-cache",
  "lookup-visited-page",
  "remember-visited-page!",
  // Retired provider/generation/weight/recency implementations.  These exact
  // legacy symbols avoid false positives from typed rejection messages that
  // deliberately name removed public options.
  "CacheValidationUpdate",
  "(defprotocol CacheStore",
  "ExactGeneration",
  "ManagedGeneration",
  "sighting-transition",
  "touch-generation!",
  "install-exact-generation!",
  "select-exact-generation!",
  "install-managed-generation!",
  "select-managed-generation!",
  "compact-entry-order",
  "compact-tombstone-order",
  "remember-tombstone",
  "publication-weight-ceiling",
  "maybe-compact-lru",
  "touch-entry!",
  "default-projection-max-weight",
  "default-denotation-max-weight",
  "default-answer-max-weight",
  "relationship-observation",
  // Deleted physical-projection and managed-subproblem storage surfaces.
  "resolve-independent!",
  "resolve-exact!",
  "resolve-bound!",
  "resolve-layered-bound!",
  "managed-subproblem-key",
  "subset-descriptor",
  "*managed-store*",
  "*managed-key-fn*",
  "*managed-scope*",
  "managed-proof-max-atoms",
  "projection-max-entries",
  // Continuation retention exposes scoped callbacks only; the old generic
  // mutation surface must not silently return.
  "(defn get!",
  "(defn put!",
];
const intentionalNonMigrations = [
  {
    owner: "DataScript adapter",
    state: "capacity-one volatile database wrapper",
    reason: "identical-database basis optimization, not a multi-entry store",
  },
  {
    owner: "generated JVM boundary",
    state: "capacity-one fuel and traversal-limit volatile wrappers",
    reason: "one mapping makes FIFO and LRU equivalent; conversion is pure",
  },
  {
    owner: "cursor key context",
    state: "at-most-eight idle JCA Mac instances per standard-LRU-owned context",
    reason:
      "bounded mutable-object pool, not keyed authorization-result retention",
  },
  {
    owner: "stable reducer",
    state: "consumable immutable sidecar chunks",
    reason:
      "request/checkpoint execution state is advanced once and never serves another computation",
  },
  {
    owner: "schema warning reporter",
    state: "saturating 256-condition first-sighting set",
    reason: "observation-only diagnostic deduplication, not result reuse",
  },
  {
    owner: "request evaluator",
    state: "memos, visited sets, queues, and worklists",
    reason: "request-owned algorithmic state",
  },
  {
    owner: "database backends",
    state: "backend-owned node and index caches",
    reason: "outside EACL authorization retention ownership",
  },
  {
    owner: "consistency selection",
    state: "authoritative snapshot and causal state",
    reason: "correctness state must not be evictable",
  },
];

function fail(message, details = undefined) {
  if (details === undefined) {
    process.stderr.write(`${message}\n`);
  } else {
    process.stderr.write(`${message}\n${JSON.stringify(details, null, 2)}\n`);
  }
  process.exit(1);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function kondoVersion() {
  const output = execFileSync("clj-kondo", ["--version"], {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
  const match = /^clj-kondo v(.+)$/.exec(output);
  if (!match) {
    fail("Unable to parse clj-kondo version.", { output });
  }
  return match[1];
}

function findForbiddenPolicyMatches(sources) {
  const matches = [];
  for (const { sourcePath, source } of sources) {
    for (const token of forbiddenPolicyTokens) {
      if (source.includes(token)) matches.push({ sourcePath, token });
    }
  }
  return matches;
}

function analyzeSource() {
  const output = execFileSync(
    "clj-kondo",
    [
      "--lint",
      ...sourcePaths,
      "--config",
      "{:output {:format :json} :analysis true :lint-as {eacl.datomic.impl/with-request-engine clojure.core/with-open}}",
      "--fail-level",
      "error",
    ],
    {
      cwd: repositoryRoot,
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    },
  );
  const parsed = JSON.parse(output);
  if (parsed.summary?.error !== 0) {
    fail("clj-kondo reported source errors.", parsed);
  }
  return parsed.analysis;
}

function qualified(namespace, name) {
  return `${namespace}/${name}`;
}

function buildReport() {
  const analysis = analyzeSource();
  const definitions = new Map();
  for (const definition of analysis["var-definitions"] ?? []) {
    if (!definition.ns?.startsWith("eacl.")) continue;
    if (definition["defined-by"]?.endsWith("/declare")) continue;
    const id = qualified(definition.ns, definition.name);
    // A .cljc var may be defined once per platform branch (a reader
    // conditional wrapping two defns); that is one logical definition
    // with a row per language, not a conflict. Same-language row or
    // privacy divergence still fails.
    const lang = definition.lang ?? "clj";
    const prior = definitions.get(id);
    if (prior) {
      const sameLangRow = prior.rowsByLang[lang];
      if (
        (sameLangRow !== undefined && sameLangRow !== definition.row) ||
        prior.private !== Boolean(definition.private)
      ) {
        fail("Conflicting source definitions in closure analysis.", {
          id,
          prior,
          definition,
        });
      }
      prior.rowsByLang[lang] = definition.row;
      prior.row = Math.min(prior.row, definition.row);
      prior.endRow = Math.max(prior.endRow ?? 0, definition["end-row"]);
      continue;
    }
    definitions.set(id, {
      id,
      name: definition.name,
      row: definition.row,
      endRow: definition["end-row"],
      private: Boolean(definition.private),
      definedBy: definition["defined-by"],
      sourcePath: definition.filename,
      rowsByLang: { [lang]: definition.row },
    });
  }

  const inlineDefinitionSpans = [...definitions.values()].filter(
    (definition) => definition.definedBy?.endsWith("/defrecord"),
  );
  // Index spans by file: the per-usage linear scan over every defrecord
  // span was O(usages x defrecords).
  const spansByPath = new Map();
  for (const definition of inlineDefinitionSpans) {
    const spans = spansByPath.get(definition.sourcePath) ?? [];
    spans.push(definition);
    spansByPath.set(definition.sourcePath, spans);
  }
  const graph = new Map();
  for (const usage of analysis["var-usages"] ?? []) {
    if (!usage.from?.startsWith("eacl.")) continue;
    const attributedDefinition =
      usage["from-var"] ??
      (spansByPath.get(usage.filename) ?? []).find(
        (definition) =>
          definition.row <= usage.row && usage.row <= definition.endRow,
      )?.name;
    if (!attributedDefinition) continue;
    const from = qualified(usage.from, attributedDefinition);
    const toNamespace = usage.to ?? "unresolved";
    const to = qualified(toNamespace, usage.name);
    if (!graph.has(from)) graph.set(from, new Set());
    graph.get(from).add(to);
  }

  const rootReports = {};
  const union = new Set();
  for (const root of roots) {
    if (!definitions.has(root)) {
      fail("Public source-closure root is missing.", { root });
    }
    const reachable = new Set([root]);
    const pending = [root];
    while (pending.length > 0) {
      const current = pending.shift();
      for (const target of graph.get(current) ?? []) {
        if (definitions.has(target) && !reachable.has(target)) {
          reachable.add(target);
          pending.push(target);
        }
      }
    }

    const unresolvedInternal = [...reachable]
      .filter((id) => !definitions.has(id))
      .sort();
    if (unresolvedInternal.length > 0) {
      fail("Reachable engine symbols lack definitions.", {
        root,
        unresolvedInternal,
      });
    }

    const externalCalls = new Set();
    for (const id of reachable) {
      for (const target of graph.get(id) ?? []) {
        const slash = target.indexOf("/");
        const namespace = slash < 0 ? target : target.slice(0, slash);
        if (!definitions.has(target) && !ignoredRuntimeNamespaces.has(namespace)) {
          externalCalls.add(target);
        }
      }
    }

    for (const id of reachable) union.add(id);
    rootReports[root] = {
      internalDefinitionCount: reachable.size,
      internalDefinitionIds: [...reachable].sort(),
      externalCallCount: externalCalls.size,
      externalCalls: [...externalCalls].sort(),
    };
  }

  const unionDefinitions = [...union]
    .sort()
    .map((id) => definitions.get(id));
  const digestedRootReports = Object.fromEntries(
    Object.entries(rootReports).map(([root, rootReport]) => [
      root,
      {
        internalDefinitionCount: rootReport.internalDefinitionCount,
        internalDefinitionIdsSha256: sha256(
          `${rootReport.internalDefinitionIds.join("\n")}\n`,
        ),
        externalCallCount: rootReport.externalCallCount,
        externalCalls: rootReport.externalCalls,
      },
    ]),
  );
  const analyzedSourcePaths = [
    ...new Set(
      [...definitions.values()]
        .map((definition) => definition.sourcePath)
        .filter(Boolean),
    ),
  ].sort();
  const forbiddenPolicyMatches = findForbiddenPolicyMatches(
    analyzedSourcePaths.map((sourcePath) => ({
      sourcePath,
      source: readFileSync(resolve(repositoryRoot, sourcePath), "utf8"),
    })),
  );
  if (forbiddenPolicyMatches.length > 0) {
    fail(
      "Policy-specific cache authority or externalized page-navigation state re-entered production source.",
      { forbiddenPolicyMatches },
    );
  }
  return {
    schemaVersion: 1,
    status: "closure-enumerated-verification-incomplete",
    assurance:
      "static-source-call-closure-not-source-refinement-or-runtime-proof",
    analyzer: {
      name: "clj-kondo",
      version: kondoVersion(),
      languagePasses: ["clj", "cljs"],
    },
    sources: analyzedSourcePaths.map((path) => ({
      path,
      sha256: sha256(readFileSync(resolve(repositoryRoot, path))),
    })),
    scope: {
      roots,
      rootCount: roots.length,
      unionInternalDefinitionCount: union.size,
      unionInternalDefinitionIdsSha256: sha256(
        `${unionDefinitions.map((definition) => definition.id).join("\n")}\n`,
      ),
      unionDefinitionLocationsSha256: sha256(
        `${unionDefinitions
          .map(
            (definition) =>
              `${definition.id}\t${definition.sourcePath}\t${definition.row}\t${definition.endRow}\t${definition.definedBy}\t${definition.private}`,
          )
          .join("\n")}\n`,
      ),
      definitionsBySource: Object.fromEntries(
        Object.entries(
          Object.groupBy(
            unionDefinitions,
            (definition) => definition.sourcePath,
          ),
        )
          .sort(([left], [right]) => left.localeCompare(right))
          .map(([path, sourceDefinitions]) => [
            path,
            {
              count: sourceDefinitions.length,
              idsSha256: sha256(
                `${sourceDefinitions
                  .map((definition) => definition.id)
                  .sort()
                  .join("\n")}\n`,
              ),
            },
          ]),
      ),
      exclusions: {
        adapterSemantics:
          "backend-dispatch.edn closes literal operation keys, but adapter operation semantics remain named trusted obligations rather than source-refined theorems",
        assurance:
          "presence in this closure does not imply theorem coverage",
        intentionalNonMigrations,
      },
      policySpecificDecisionGuard: {
        status: "passed",
        forbiddenTokens: forbiddenPolicyTokens,
      },
    },
    attribution: {
      inlineDefrecordMethods:
        "unattributed usages inside exact clj-kondo defrecord source spans are assigned to the containing record root",
      inlineDefrecordCount: inlineDefinitionSpans.length,
    },
    roots: digestedRootReports,
  };
}

const mode = process.argv[2] ?? "check";
if (!["check", "write", "json", "selftest"].includes(mode)) {
  fail("usage: bin/public-source-closure.mjs [check|write|json|selftest]");
}

if (mode === "selftest") {
  // Positive control: every forbidden token planted in a synthetic source
  // must be detected by the real scan (an always-empty detector fails here).
  const matches = findForbiddenPolicyMatches(
    forbiddenPolicyTokens.map((token) => ({
      sourcePath: `synthetic/${token}`,
      source: `(def ${token} :reintroduced)`,
    })),
  );
  const detected = new Set(matches.map(({ token }) => token));
  const missing = forbiddenPolicyTokens.filter((token) => !detected.has(token));
  if (missing.length > 0) {
    fail("Policy-specific decision guard failed its positive self-test.", {
      missing,
    });
  }
  // Negative controls: a clean source and a near-miss (token with its last
  // character doubled) must produce zero matches (an always-match or
  // over-broad detector fails here).
  const cleanSources = [
    { sourcePath: "synthetic/clean", source: "(def unrelated :value)" },
    ...forbiddenPolicyTokens.map((token) => ({
      sourcePath: `synthetic/near-miss/${token}`,
      source: `(def ${token.slice(0, -1)}_x :value)`,
    })),
  ];
  const falsePositives = findForbiddenPolicyMatches(cleanSources);
  if (falsePositives.length > 0) {
    fail("Policy-specific decision guard failed its negative self-test.", {
      falsePositives: falsePositives.slice(0, 5),
    });
  }
  process.stdout.write(
    `${JSON.stringify({
      status: "passed",
      detected: detected.size,
      negativeControls: cleanSources.length,
    })}\n`,
  );
  process.exit(0);
}

const report = buildReport();
const encoded = `${JSON.stringify(report, null, 2)}\n`;
if (mode === "json") {
  process.stdout.write(encoded);
} else {
  const absoluteReportPath = resolve(repositoryRoot, reportPath);
  mkdirSync(dirname(absoluteReportPath), { recursive: true });
  writeFileSync(absoluteReportPath, encoded);
  process.stdout.write(
    `${JSON.stringify({
      status: mode === "check" ? "passed" : "written",
      report: reportPath,
      roots: roots.length,
      definitions: report.scope.unionInternalDefinitionCount,
      forbiddenPolicyMatches: 0,
    })}\n`,
  );
}
