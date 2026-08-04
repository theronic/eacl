"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const generatedPath =
  process.env.EACL_GENERATED_JS ||
  path.resolve(__dirname, "../../../target/formal/js/EaclKernel.js");
const source = fs.readFileSync(generatedPath, "utf8");
const context = {
  console,
  module: { exports: {} },
  require,
};

vm.runInNewContext(
  `${source}
module.exports = {
  EaclKernel,
  Semantics,
  AcyclicEngine,
  CacheKernel,
  ConsistencyDecision,
  CurrentCache,
  IndexedCertification,
  IndexedRefinement,
  IndexedTraversal,
  OrderedMerge,
  PageWindow,
  Pagination,
  RecursiveEngine,
  SubproblemCache,
  TemporalSafety,
  WireFormat,
  _dafny,
  BigNumber,
};`,
  context,
  { filename: generatedPath },
);

module.exports = context.module.exports;
