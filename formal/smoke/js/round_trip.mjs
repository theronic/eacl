import assert from "node:assert/strict";
import fs from "node:fs";
import { createRequire } from "node:module";
import vm from "node:vm";

const generatedPath = process.argv[2];
if (!generatedPath) {
  throw new Error("expected the generated EaclKernel.js path");
}

const source = fs.readFileSync(generatedPath, "utf8");
const context = {
  console,
  module: { exports: {} },
  require: createRequire(import.meta.url),
};
vm.runInNewContext(
  `${source}
module.exports = { EaclKernel, _dafny, BigNumber };`,
  context,
  { filename: generatedPath },
);

const { EaclKernel, _dafny, BigNumber } = context.module.exports;
const roundTrip = EaclKernel.__default.RoundTrip;
assert.equal(typeof roundTrip, "function", "generated RoundTrip export is missing");

const accepted = roundTrip(
  _dafny.Seq.UnicodeFromString("eacl.round-trip/v1"),
  new _dafny.Seq(...[0, 7, 42].map((n) => new BigNumber(n))),
  new BigNumber(3),
);
assert.equal(accepted.is_Accepted, true);
assert.deepEqual(
  Array.from(accepted.dtor_items, (n) => n.toNumber()),
  [0, 7, 42],
);

const invoke = (tag, values, limit) =>
  roundTrip(
    _dafny.Seq.UnicodeFromString(tag),
    new _dafny.Seq(...values.map((n) => new BigNumber(n))),
    new BigNumber(limit),
  );
assert.equal(invoke("unknown", [1], 1).is_Rejected, true);
assert.equal(invoke("eacl.round-trip/v1", [1, -1], 2).is_Rejected, true);
assert.equal(invoke("eacl.round-trip/v1", [1, 2], 1).is_Rejected, true);

console.log("Generated JavaScript value/collection/error boundary executed.");
