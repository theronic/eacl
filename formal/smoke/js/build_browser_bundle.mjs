import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as esbuild from "esbuild";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../../..");
const generatedPath = path.join(
  repoRoot,
  "target/formal/js/EaclKernel.js",
);
const outputDirectory = path.join(repoRoot, "target/formal/browser");
const generated = fs.readFileSync(generatedPath, "utf8");

fs.mkdirSync(outputDirectory, { recursive: true });

await esbuild.build({
  stdin: {
    contents: `${generated}
globalThis.EaclFormal = {
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
  RoutingCertificate,
  SubproblemCache,
  TemporalSafety,
  WireFormat,
  _dafny,
  BigNumber,
};`,
    loader: "js",
    resolveDir: here,
    sourcefile: "EaclKernel.generated.js",
  },
  bundle: true,
  format: "iife",
  minify: true,
  platform: "browser",
  target: ["es2020"],
  outfile: path.join(outputDirectory, "EaclKernel.browser.js"),
  sourcemap: true,
});

console.log("Generated browser bundle built with esbuild.");
