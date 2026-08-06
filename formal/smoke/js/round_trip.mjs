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

const asNumbers = (values) =>
  Array.from(values, (value) => value.toNumber());
const numberSequence = (values) =>
  _dafny.Seq.of(...values.map((value) => new BigNumber(value)));

const flatSequence = numberSequence([0, 1, 2, 3, 4]);
const suffixView = flatSequence.slice(new BigNumber(1), new BigNumber(4));
assert.equal(Array.isArray(suffixView), true);
assert.equal(suffixView.length, 3);
assert.equal(suffixView[new BigNumber(0)].toNumber(), 1);
assert.deepEqual(asNumbers(suffixView), [1, 2, 3]);
assert.deepEqual(asNumbers(flatSequence), [0, 1, 2, 3, 4]);

let concatenated = _dafny.Seq.of();
for (let value = 0; value < 4096; value++) {
  concatenated = _dafny.Seq.Concat(
    concatenated,
    numberSequence([value]),
  );
}
assert.equal(concatenated.length, 4096);
assert.equal(
  Object.getOwnPropertyDescriptor(concatenated, "length").value,
  0,
  "persistent sequence views must not allocate sparse Array backing stores",
);
assert.equal(concatenated[0].toNumber(), 0);
assert.equal(concatenated[4095].toNumber(), 4095);
assert.equal(
  concatenated.equals(
    numberSequence(Array.from({length: 4096}, (_, index) => index)),
  ),
  true,
);

const structuralKey = (left, right) =>
  numberSequence([left, right]);
const firstKey = structuralKey(1, 2);
const equalFirstKey = structuralKey(1, 2);
const secondKey = structuralKey(3, 4);
const firstSet = _dafny.Set.fromElements(firstKey);
const expandedSet = firstSet.Union(_dafny.Set.fromElements(secondKey));
assert.equal(firstSet.contains(equalFirstKey), true);
assert.equal(firstSet.contains(secondKey), false);
assert.equal(expandedSet.contains(equalFirstKey), true);
assert.equal(expandedSet.contains(secondKey), true);

const firstMap = _dafny.Map.of([firstKey, "first"]);
const updatedMap = firstMap.update(equalFirstKey, "updated");
assert.equal(firstMap.get(equalFirstKey), "first");
assert.equal(updatedMap.get(firstKey), "updated");
assert.equal(updatedMap.length, 1);

class CollidingKey {
  constructor(id) {
    this.id = id;
  }
  equals(other) {
    return other instanceof CollidingKey && this.id === other.id;
  }
  toString() {
    return "intentional-hash-collision";
  }
}
const collidingSet = _dafny.Set.fromElements(
  new CollidingKey(1),
  new CollidingKey(2),
);
assert.equal(collidingSet.length, 2);
assert.equal(collidingSet.contains(new CollidingKey(1)), true);
assert.equal(collidingSet.contains(new CollidingKey(2)), true);

console.log("Generated JavaScript value/collection/error boundary executed.");
