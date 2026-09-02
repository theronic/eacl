#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(
  process.env.EACL_REPO_ROOT ??
    resolve(dirname(fileURLToPath(import.meta.url)), ".."),
);
const closureScript = resolve(repositoryRoot, "bin/public-source-closure.mjs");
const currentPath = resolve(
  repositoryRoot,
  process.env.EACL_PUBLIC_SOURCE_CLOSURE_OUTPUT ??
    "target/formal/verification/public-source-closure.json",
);
const baseRef = process.argv[2];

function reportRow(label, report) {
  return `| ${label} | ${report.sources.length} | ${report.scope.rootCount} | ${report.scope.unionInternalDefinitionCount} | \`${report.scope.unionInternalDefinitionIdsSha256.slice(0, 12)}\` |`;
}

function rootFingerprint(root) {
  return JSON.stringify([
    root.internalDefinitionIdsSha256,
    root.externalCalls,
  ]);
}

const current = JSON.parse(readFileSync(currentPath, "utf8"));
const headSha = execFileSync("git", ["rev-parse", "HEAD"], {
  cwd: repositoryRoot,
  encoding: "utf8",
}).trim();

let markdown = [
  "## Formal assurance source closure",
  "",
  "| Revision | Source files | Public roots | Reachable definitions | Closure digest |",
  "| --- | ---: | ---: | ---: | --- |",
];

if (baseRef) {
  const baseSha = execFileSync("git", ["merge-base", "HEAD", baseRef], {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
  const worktree = mkdtempSync(join(tmpdir(), "eacl-assurance-base-"));
  try {
    execFileSync("git", ["worktree", "add", "--detach", worktree, baseSha], {
      cwd: repositoryRoot,
      stdio: "ignore",
    });
    const base = JSON.parse(
      execFileSync(process.execPath, [closureScript, "json"], {
        cwd: repositoryRoot,
        encoding: "utf8",
        maxBuffer: 64 * 1024 * 1024,
        env: { ...process.env, EACL_REPO_ROOT: worktree },
      }),
    );
    const allRoots = new Set([
      ...Object.keys(base.roots),
      ...Object.keys(current.roots),
    ]);
    const changedRoots = [...allRoots]
      .filter(
        (root) =>
          rootFingerprint(base.roots[root] ?? {}) !==
          rootFingerprint(current.roots[root] ?? {}),
      )
      .sort();
    markdown.push(
      reportRow(`base \`${baseSha.slice(0, 12)}\``, base),
      reportRow(`head \`${headSha.slice(0, 12)}\``, current),
      "",
      `Changed public roots: **${changedRoots.length}**`,
      "",
      changedRoots.length === 0
        ? "No public-root closure changed."
        : changedRoots.map((root) => `- \`${root}\``).join("\n"),
    );
  } finally {
    try {
      execFileSync("git", ["worktree", "remove", "--force", worktree], {
        cwd: repositoryRoot,
        stdio: "ignore",
      });
    } finally {
      rmSync(worktree, { recursive: true, force: true });
    }
  }
} else {
  markdown.push(
    reportRow(`head \`${headSha.slice(0, 12)}\``, current),
    "",
    "No base revision was supplied; this is a current-only summary.",
  );
}

process.stdout.write(`${markdown.join("\n")}\n`);
