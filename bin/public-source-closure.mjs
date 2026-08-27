#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourcePaths = [
  "modules/eacl/src",
  "modules/eacl-datomic/src",
  "modules/eacl-datahike/src",
  "modules/eacl-datascript/src",
  "modules/eacl-datalevin/src",
];
const reportPath = "formal/verification/public-source-closure.json";
const toolchainPath = "formal/toolchain.lock.json";
const roots = [
  "eacl.engine.v8/can?",
  "eacl.engine.v8/lookup-resources",
  "eacl.engine.v8/lookup-subjects",
  "eacl.engine.v8/count-resources",
  "eacl.engine.v8/count-subjects",
  "eacl.engine.relationships/execute-page",
  "eacl.engine.relationships/execute-plan",
  "eacl.relay/lookup-visited-page",
  "eacl.relay/remember-visited-page!",
  "eacl.relay/select-continuation-adapter",
  "eacl.relay/prepare-page-query",
  "eacl.relay/internalize-page-query",
  "eacl.relay/externalize-page",
  "eacl.relay/externalize-relationship-page",
  "eacl.cursor/cursor->token",
  "eacl.cursor/token->cursor",
  "eacl.cache/resolve-basis!",
  "eacl.cache/resolve-managed-read-only!",
  "eacl.subproblem-cache/resolve!",
  "eacl.subproblem-cache/resolve-bound!",
  "eacl.subproblem-cache/resolve-layered-bound!",
  "eacl.subproblem-cache/lookup!",
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
  "eacl.client.orchestration/make-client",
  "eacl.datomic.core/make-client",
  "eacl.datomic.core/db",
  "eacl.datomic.core/current-zed-token",
  "eacl.datomic.core/basis-instant",
  "eacl.datahike.core/make-client",
  "eacl.datahike.core/db",
  "eacl.datascript.core/make-client",
  "eacl.datascript.core/db",
  "eacl.datalevin.core/make-client",
  "eacl.datalevin.core/db",
];
const ignoredRuntimeNamespaces = new Set(["clojure.core", "cljs.core"]);

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

function exactKondoVersion() {
  const output = execFileSync("clj-kondo", ["--version"], {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
  const match = /^clj-kondo v(.+)$/.exec(output);
  if (!match) {
    fail("Unable to parse clj-kondo version.", { output });
  }
  const actual = match[1];
  const toolchain = JSON.parse(
    readFileSync(resolve(repositoryRoot, toolchainPath), "utf8"),
  );
  const expected = toolchain.tools?.cljKondo?.version;
  if (!expected || actual !== expected) {
    fail("clj-kondo does not match the formal toolchain lock.", {
      expected,
      actual,
    });
  }
  return actual;
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
    const prior = definitions.get(id);
    if (
      prior &&
      (prior.row !== definition.row ||
        prior.private !== Boolean(definition.private))
    ) {
      fail("Conflicting source definitions in closure analysis.", {
        id,
        prior,
        definition,
      });
    }
    definitions.set(id, {
      id,
      name: definition.name,
      row: definition.row,
      endRow: definition["end-row"],
      private: Boolean(definition.private),
      definedBy: definition["defined-by"],
      sourcePath: definition.filename,
    });
  }

  const inlineDefinitionSpans = [...definitions.values()].filter(
    (definition) => definition.definedBy?.endsWith("/defrecord"),
  );
  const graph = new Map();
  for (const usage of analysis["var-usages"] ?? []) {
    if (!usage.from?.startsWith("eacl.")) continue;
    const attributedDefinition =
      usage["from-var"] ??
      inlineDefinitionSpans.find(
        (definition) =>
          definition.sourcePath === usage.filename &&
          definition.row <= usage.row &&
          usage.row <= definition.endRow,
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
  const sourcePaths = [
    ...new Set(
      [...definitions.values()]
        .map((definition) => definition.sourcePath)
        .filter(Boolean),
    ),
  ].sort();
  return {
    schemaVersion: 1,
    status: "closure-enumerated-verification-incomplete",
    assurance:
      "static-source-call-closure-not-source-refinement-or-runtime-proof",
    analyzer: {
      name: "clj-kondo",
      version: exactKondoVersion(),
      languagePasses: ["clj", "cljs"],
    },
    sources: sourcePaths.map((path) => ({
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
if (!["check", "write"].includes(mode)) {
  fail("usage: bin/public-source-closure.mjs [check|write]");
}

const report = buildReport();
const encoded = `${JSON.stringify(report, null, 2)}\n`;
const absoluteReportPath = resolve(repositoryRoot, reportPath);
if (mode === "write") {
  writeFileSync(absoluteReportPath, encoded);
  process.stdout.write(
    `${JSON.stringify({
      status: "written",
      report: reportPath,
      roots: roots.length,
      definitions: report.scope.unionInternalDefinitionCount,
    })}\n`,
  );
} else {
  const committed = readFileSync(absoluteReportPath, "utf8");
  if (committed !== encoded) {
    fail(
      "Public source closure changed. Review every added/removed decision dependency, then regenerate with `bin/public-source-closure.mjs write`.",
      {
        expectedSha256: sha256(committed),
        actualSha256: sha256(encoded),
      },
    );
  }
  process.stdout.write(
    `${JSON.stringify({
      status: "passed",
      report: reportPath,
      roots: roots.length,
      definitions: report.scope.unionInternalDefinitionCount,
    })}\n`,
  );
}
