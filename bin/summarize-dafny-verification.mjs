#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

function fail(message, details = {}) {
  process.stderr.write(`${message}\n${JSON.stringify(details, null, 2)}\n`);
  process.exit(1);
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;

  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      if (character === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        field += character;
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === ",") {
      row.push(field);
      field = "";
    } else if (character === "\n") {
      row.push(field.replace(/\r$/, ""));
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += character;
    }
  }

  if (quoted) {
    fail("Unterminated quoted field in Dafny CSV output.");
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field.replace(/\r$/, ""));
    rows.push(row);
  }
  return rows.filter((candidate) =>
    candidate.some((value) => value.length > 0));
}

function durationSeconds(duration) {
  const match = /^(\d+):(\d+):(\d+(?:\.\d+)?)$/.exec(duration);
  if (!match) {
    fail("Unexpected Dafny duration.", { duration });
  }
  return (
    Number(match[1]) * 3600
    + Number(match[2]) * 60
    + Number(match[3])
  );
}

const [directory, rawLimit, output] = process.argv.slice(2);
if (!directory || !rawLimit || !output) {
  fail(
    "usage: summarize-dafny-verification.mjs <csv-dir> <resource-limit> <output-json>",
  );
}

const resourceLimit = Number(rawLimit);
if (!Number.isSafeInteger(resourceLimit) || resourceLimit <= 0) {
  fail("Dafny proof-effort resource limit must be a positive safe integer.", {
    rawLimit,
  });
}

const csvFiles = fs.readdirSync(directory)
  .filter((file) => file.endsWith(".csv"))
  .sort();
if (csvFiles.length === 0) {
  fail("No Dafny CSV verification results found.", { directory });
}

const modules = csvFiles.map((file) => {
  const rows = parseCsv(fs.readFileSync(path.join(directory, file), "utf8"));
  const [header, ...results] = rows;
  const expectedHeader = [
    "TestResult.DisplayName",
    "TestResult.Outcome",
    "TestResult.Duration",
    "TestResult.ResourceCount",
    "RandomSeed",
  ];
  if (JSON.stringify(header) !== JSON.stringify(expectedHeader)) {
    fail("Unexpected Dafny CSV schema.", { file, header });
  }
  if (results.length === 0) {
    fail("Dafny CSV contains no proof efforts.", { file });
  }

  let totalResourceCount = 0;
  let totalSolverSeconds = 0;
  let maximum = null;
  for (const result of results) {
    if (result.length !== expectedHeader.length) {
      fail("Malformed Dafny CSV row.", { file, result });
    }
    const [displayName, outcome, duration, rawResourceCount, randomSeed] =
      result;
    const resourceCount = Number(rawResourceCount);
    if (outcome !== "Passed") {
      fail("Dafny proof effort did not pass.", {
        file,
        displayName,
        outcome,
      });
    }
    if (!Number.isSafeInteger(resourceCount) || resourceCount < 0) {
      fail("Invalid Dafny resource count.", {
        file,
        displayName,
        rawResourceCount,
      });
    }
    if (resourceCount > resourceLimit) {
      fail("Dafny proof effort exceeded the deterministic resource limit.", {
        file,
        displayName,
        resourceCount,
        resourceLimit,
      });
    }
    totalResourceCount += resourceCount;
    totalSolverSeconds += durationSeconds(duration);
    if (!maximum || maximum.resourceCount < resourceCount) {
      maximum = {
        displayName,
        resourceCount,
        solverSeconds: durationSeconds(duration),
        randomSeed: Number(randomSeed),
      };
    }
  }

  return {
    source: file.replace(/\.csv$/, ".dfy"),
    proofEfforts: results.length,
    totalResourceCount,
    totalSolverSeconds,
    maximumProofEffort: maximum,
  };
});

const aggregate = modules.reduce(
  (summary, module) => ({
    proofEfforts: summary.proofEfforts + module.proofEfforts,
    totalResourceCount:
      summary.totalResourceCount + module.totalResourceCount,
    totalSolverSeconds:
      summary.totalSolverSeconds + module.totalSolverSeconds,
    maximumProofEffort:
      !summary.maximumProofEffort
      || summary.maximumProofEffort.resourceCount
        < module.maximumProofEffort.resourceCount
        ? { source: module.source, ...module.maximumProofEffort }
        : summary.maximumProofEffort,
  }),
  {
    proofEfforts: 0,
    totalResourceCount: 0,
    totalSolverSeconds: 0,
    maximumProofEffort: null,
  },
);

const report = {
  schemaVersion: 1,
  assurance: {
    status: "passed",
    scope: "dafny-solver-proof-effort-resource",
    notEngineRuntimeOrMemoryEvidence: true,
  },
  policy: {
    proofEffortResourceLimit: resourceLimit,
    resourceKind: "z3-rlimit",
    deterministicForLockedToolchainAndSeed: true,
  },
  aggregate,
  modules,
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
process.stdout.write(
  `${JSON.stringify({
    status: "passed",
    output,
    modules: modules.length,
    proofEfforts: aggregate.proofEfforts,
    maximumProofEffort: aggregate.maximumProofEffort,
  })}\n`,
);
