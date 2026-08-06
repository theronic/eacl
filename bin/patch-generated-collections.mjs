#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repository = path.resolve(here, "..");
const target = process.argv[2];

const fail = message => {
  throw new Error(`generated collection patch failed: ${message}`);
};

const replaceExactlyOnce = (source, start, end, replacement, label) => {
  const startIndex = source.indexOf(start);
  if (startIndex < 0 || source.indexOf(start, startIndex + 1) >= 0) {
    fail(`${label} start marker was not unique`);
  }
  const endIndex = source.indexOf(end, startIndex + start.length);
  if (endIndex < 0 || source.indexOf(end, endIndex + 1) >= 0) {
    fail(`${label} end marker was not unique`);
  }
  return source.slice(0, startIndex) +
    replacement.trimEnd() + "\n" +
    source.slice(endIndex);
};

if (target === "java") {
  const generated = path.join(
    repository,
    "target/formal/java/EaclKernel-java/dafny",
  );
  for (const name of ["DafnySet.java", "DafnyMap.java"]) {
    const source = path.join(
      repository,
      "formal/runtime/java/dafny",
      name,
    );
    const destination = path.join(generated, name);
    if (!fs.existsSync(destination)) {
      fail(`missing generated Java runtime ${destination}`);
    }
    fs.copyFileSync(source, destination);
  }
  console.log("Patched generated Java sets/maps with persistent collections.");
} else if (target === "js") {
  const generated = path.join(
    repository,
    "target/formal/js/EaclKernel.js",
  );
  let source = fs.readFileSync(generated, "utf8");
  const dependencyMarker =
    "const BigNumber = require('bignumber.js');\n";
  if (!source.includes(dependencyMarker) ||
      source.indexOf(dependencyMarker) !==
        source.lastIndexOf(dependencyMarker)) {
    fail("BigNumber dependency marker was not unique");
  }
  source = source.replace(
    dependencyMarker,
    dependencyMarker + "const Immutable = require('immutable');\n",
  );
  const setRuntime = fs.readFileSync(
    path.join(repository, "formal/runtime/js/dafny-set.js"),
    "utf8",
  );
  const mapRuntime = fs.readFileSync(
    path.join(repository, "formal/runtime/js/dafny-map.js"),
    "utf8",
  );
  const seqRuntime = fs.readFileSync(
    path.join(repository, "formal/runtime/js/dafny-seq.js"),
    "utf8",
  );
  source = replaceExactlyOnce(
    source,
    "  $module.Set = class Set extends Array {",
    "  $module.MultiSet = class MultiSet extends Array {",
    setRuntime,
    "JavaScript Set",
  );
  source = replaceExactlyOnce(
    source,
    "  $module.Seq = class Seq extends Array {",
    "  $module.Map = class Map extends Array {",
    seqRuntime,
    "JavaScript Seq",
  );
  source = replaceExactlyOnce(
    source,
    "  $module.Map = class Map extends Array {",
    "  $module.newArray = function(initValue, ...dims) {",
    mapRuntime,
    "JavaScript Map",
  );
  fs.writeFileSync(generated, source);
  console.log(
    "Patched generated JavaScript sets/maps/sequences with persistent collections.",
  );
} else {
  fail("expected target argument `java` or `js`");
}
