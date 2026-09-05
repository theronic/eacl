#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$repo_root"
: "${EACL_NREPL_PORT:?EACL_NREPL_PORT must name the project dev nREPL}"
lock_value() {
  node -e 'const fs=require("fs"); console.log(process.argv[2].split(".").reduce((v,k)=>v[k],JSON.parse(fs.readFileSync(process.argv[1],"utf8"))))' "$1" "$2"
}
dafny_version=$(lock_value formal/toolchain.lock.json tools.dafny.version)
dafny="${EACL_FORMAL_CACHE:-$repo_root/target/formal-tools}/dafny-$dafny_version/dafny/dafny"
output="${EACL_FORMAL_OUTPUT:-$repo_root/target/formal}/qualified"
mkdir -p "$output"
node formal/qualified/check-boundary.mjs
time_limit=$(lock_value formal/qualified/gate.lock.json verificationTimeLimitSeconds)
resource_limit=$(lock_value formal/qualified/gate.lock.json proofResourceLimit)
"$dafny" verify --verification-time-limit "$time_limit" --resource-limit "$resource_limit" \
  formal/dafny/QualifiedEvidence.dfy formal/dafny/QualifiedTemporal.dfy formal/dafny/QualifiedReuse.dfy \
  > "$output/proofs.log" 2>&1 || { cat "$output/proofs.log"; exit 1; }
cat "$output/proofs.log"
expected=$(lock_value formal/qualified/gate.lock.json verifiedObligations)
actual=$(awk '/Dafny program verifier finished with/ {total += $6} END {print total+0}' "$output/proofs.log")
if [ "$actual" -ne "$expected" ]; then
  printf 'Qualified proof count changed: expected %s, observed %s\n' "$expected" "$actual" >&2
  exit 1
fi
assertions=$(lock_value formal/qualified/gate.lock.json assertions)
sh bin/ci-nrepl-eval "$EACL_NREPL_PORT" \
  "(do
     (load-file \"formal/caveats/model.clj\")
     (load-file \"formal/qualified/model.clj\")
     (load-file \"formal/qualified/model_test.clj\")
     (load-file \"formal/qualified/mutation_test.clj\")
     (require 'eacl.authorization.evidence :reload)
     (require 'eacl.authorization.evidence-test :reload)
     (load-file \"formal/qualified/evidence_bridge.clj\")
     (let [r (clojure.test/run-tests 'eacl.formal.qualified.model-test 'eacl.formal.qualified.mutation-test
                                   'eacl.formal.qualified.evidence-bridge)]
       (when (or (pos? (+ (:fail r) (:error r))) (not= $assertions (:pass r)))
         (throw (ex-info \"Qualified finite gate failed or assertion inventory changed\" r)))
       r))" > "$output/finite.log" 2>&1 || { cat "$output/finite.log"; exit 1; }
cat "$output/finite.log"
