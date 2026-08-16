#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
jvm_tmp="$repo_root/target/exploration/stable-discovery/jvm-tmp/qualify-forward-runtime"
mkdir -p "$jvm_tmp"

cd "$repo_root"
clojure -J-Djava.io.tmpdir="$jvm_tmp" -M:dev -e '
  (do
    (load-file "target/exploration/stable-discovery/source_benchmark.clj")
    (load-file "target/exploration/stable-discovery/sealed_plan_refinement_bridge.clj")
    (load-file "target/exploration/stable-discovery/forward_runtime_prototype.clj")
    (prn
     {:adversarial
      (eacl.exploration.forward-runtime-prototype/benchmark-adversarial!
       2000 20)
      :recursive
      (eacl.exploration.forward-runtime-prototype/qualify-recursive! 20)
      :reverse
      (eacl.exploration.forward-runtime-prototype/qualify-reverse! 20)
      :static-edge-cases
      (eacl.exploration.forward-runtime-prototype/qualify-static-edge-cases!)
      :one-value-width-invariance
      (eacl.exploration.forward-runtime-prototype/qualify-one-value-width-invariance!)
      :sidecar-capacity
      (eacl.exploration.forward-runtime-prototype/qualify-sidecar-cap!)}))'
