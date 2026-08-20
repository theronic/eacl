#!/bin/sh
# Fast release-assurance gate for the stable-discovery engine
# (adopt-stable-discovery-enumeration, tasks 3.1/3.5).
#
# Retained scope only: denotation/grounding, sealed plan + rank, the generic
# reducer family, one-value normalization, pagination/edge/checkpoint
# composition, atomic admission and attempt outcomes, cancellation, and the
# representation leaves — plus the retained TLC families (AtomicAttempt,
# ProgressCheckpoint) and the retained CLJ bridges. The parked concurrency
# models (ReducerReadAhead, DescriptorCoalescing, ServiceLifecycle,
# ReadableWorkIndex.dfy, WeightedResponseLease.dfy, the physical-scheduler
# bridge) live only in exploration/stable-discovery/ for the future
# concurrency change. See README.md for the full disposition table.
set -eu

gate_started_at=$(date +%s)
gate_hard_limit_seconds=10

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
model_root="$repo_root/formal/stable-discovery"
dafny="$repo_root/target/formal-tools/dafny-4.11.0/dafny/dafny"
tla_jar="$repo_root/target/formal-tools/tla2tools-1.7.4.jar"
tlc_runner_source="$model_root/TLCFamilyRunner.java"
tlc_runner_classes="$repo_root/target/formal/stable-discovery-gate/tlc-runner-classes"
tlc_runner_class="$tlc_runner_classes/TLCFamilyRunner.class"
tlc_run_root="$repo_root/target/formal/stable-discovery-gate/tlc-runs/$$"
mkdir -p "$tlc_run_root"

discovered_dafny_models=$(
  find "$model_root" -type f -name '*.dfy' |
    sed 's#.*/##' |
    sort
)
declared_dafny_models=$(
  sed -n \
    's#^[[:space:]]*"\$model_root/\([A-Za-z0-9]*\.dfy\)".*#\1#p' \
    "$0" |
    sort
)
if [ "$discovered_dafny_models" != "$declared_dafny_models" ]; then
  printf 'Dafny manifest mismatch. Discovered:\n%s\nDeclared once:\n%s\n' \
    "$discovered_dafny_models" "$declared_dafny_models" >&2
  exit 1
fi

discovered_tlc_configs=$(
  find "$model_root" -type f -name '*.cfg' |
    sed 's#.*/##' |
    sort
)
declared_tlc_configs=$(
  sed -n \
    -e 's/^[[:space:]]*valid \([^ ]*\.cfg\) .*/\1/p' \
    -e 's/^[[:space:]]*mutant \([^ ]*\.cfg\) .*/\1/p' \
    "$0" |
    sort
)
if [ "$discovered_tlc_configs" != "$declared_tlc_configs" ]; then
  printf 'TLC manifest mismatch. Discovered:\n%s\nDeclared once:\n%s\n' \
    "$discovered_tlc_configs" "$declared_tlc_configs" >&2
  exit 1
fi

discovered_source_bridges=$(
  find "$model_root" -type f -name '*_refinement_bridge.clj' |
    sed 's#.*/##' |
    sort
)
declared_source_bridges=$(
  sed -n \
    's#.*load-file "formal/stable-discovery/\([a-z_]*_refinement_bridge\.clj\)".*#\1#p' \
    "$0" |
    sort
)
if [ "$discovered_source_bridges" != "$declared_source_bridges" ]; then
  printf 'Source-refinement manifest mismatch. Discovered:\n%s\nDeclared once:\n%s\n' \
    "$discovered_source_bridges" "$declared_source_bridges" >&2
  exit 1
fi

# grep rather than rg: a missing scanner must fail the gate, not silently
# skip the escape-hatch check.
if ! command -v grep >/dev/null 2>&1; then
  printf 'grep is required for the Dafny escape-hatch scan.\n' >&2
  exit 1
fi
if grep -rEn --include='*.dfy' \
    '^[[:space:]]*(assume|axiom)\b|\{:[[:space:]]*(axiom|extern)\b|\{:[[:space:]]*verify[[:space:]]+false\b|decreases[[:space:]]+\*' \
    "$model_root"
then
  printf 'Dafny proof escape hatch detected; isolate and review it explicitly.\n' >&2
  exit 1
fi

tla_assumption_fingerprint=$(
  cd "$model_root"
  tla_models=$(find . -maxdepth 1 -type f -name '*.tla' |
    sed 's#^\./##' |
    sort)
  # TLA+ ASSUME is legitimate for finite fixture domains and constant types,
  # but changing those premises is a proof-boundary change and must be
  # consciously re-audited rather than silently making TLC faster.
  awk \
    '/^ASSUME[[:space:]]*$/{capture=1} capture {print FILENAME ":" $0} capture && /^[[:space:]]*$/{capture=0}' \
    $tla_models |
    cksum |
    awk '{print $1 ":" $2}'
)
expected_tla_assumption_fingerprint='36487614:739'
if [ "$tla_assumption_fingerprint" != "$expected_tla_assumption_fingerprint" ]; then
  printf 'TLA+ assumption boundary changed: expected %s, observed %s\n' \
    "$expected_tla_assumption_fingerprint" "$tla_assumption_fingerprint" >&2
  exit 1
fi

dafny_check_one() {
  "$dafny" verify \
    --verification-time-limit 15 \
    "$model_root/StableReducer.dfy" \
    "$model_root/HistoryFreeReducer.dfy" \
    "$model_root/TargetedResultDriver.dfy" \
    "$model_root/ConcreteHistoryFreeRuntime.dfy" \
    "$model_root/OwnedTransientSnapshot.dfy" \
    "$model_root/ReducerCompleteness.dfy" \
    "$model_root/ExactCountComposition.dfy" \
    "$model_root/StaticReverseFrontier.dfy"
}

dafny_check_two() {
  "$dafny" verify \
    --verification-time-limit 15 \
    "$model_root/BidirectionalReachability.dfy" \
    "$model_root/BidirectionalArrowIntersection.dfy" \
    "$model_root/EaclBidirectionalReachability.dfy" \
    "$model_root/ChunkedScan.dfy" \
    "$model_root/DescriptorIdentity.dfy" \
    "$model_root/CacheBoundary.dfy" \
    "$model_root/StablePagination.dfy" \
    "$model_root/RelayEdgePagination.dfy" \
    "$model_root/EdgeBoundaryAuthentication.dfy" \
    "$model_root/RelayCheckpointExecution.dfy" \
    "$model_root/LookaheadPagination.dfy" \
    "$model_root/PaginationComposition.dfy"
}

dafny_check_three() {
  "$dafny" verify \
    --verification-time-limit 15 \
    "$model_root/BoundedPageBuffer.dfy" \
    "$model_root/RuntimeCheckpointComposition.dfy" \
    "$model_root/ConcreteOutputIdentity.dfy" \
    "$model_root/AtomicLogicalAdmission.dfy" \
    "$model_root/RuntimeStackRefinement.dfy" \
    "$model_root/LogicalScanCursor.dfy" \
    "$model_root/OneValueScanNormalization.dfy" \
    "$model_root/ReducerCost.dfy" \
    "$model_root/GroundedPositiveProgram.dfy" \
    "$model_root/ExactDedupLowerBound.dfy" \
    "$model_root/MembershipProbeCheck.dfy"
}

dafny_check_four() {
  "$dafny" verify \
    --verification-time-limit 15 \
    "$model_root/EaclForwardGrounding.dfy" \
    "$model_root/BoundedSidecar.dfy" \
    "$model_root/EaclForwardProducer.dfy" \
    "$model_root/EaclReverseProducer.dfy" \
    "$model_root/StaticDirectionIndex.dfy" \
    "$model_root/ReducerCheckpoint.dfy" \
    "$model_root/WeightedCheckpointSlot.dfy" \
    "$model_root/OrderIrrelevance.dfy" \
    "$model_root/ReadRankCertificate.dfy" \
    "$model_root/SealedVectorOrder.dfy" \
    "$model_root/SealedPlanReducerComposition.dfy" \
    "$model_root/RecordFraming.dfy"
}

source_refinement_check() {
  source_refinement_tmp="$tlc_run_root/source-refinement-tmp"
  mkdir -p "$source_refinement_tmp"
  (
    cd "$repo_root"
    clojure -J-Djava.io.tmpdir="$source_refinement_tmp" -M:dev -e \
      '(do
         (load-file "formal/stable-discovery/randomized_refinement.clj")
         (load-file "formal/stable-discovery/public_schema_refinement_bridge.clj")
         (load-file "formal/stable-discovery/sealed_plan_refinement_bridge.clj")
         (load-file "formal/stable-discovery/cursor_refinement_bridge.clj")
         (load-file "formal/stable-discovery/progress_checkpoint_refinement_bridge.clj")
         (prn (eacl.exploration.randomized-refinement/run-campaign! 24301 2000))
         (prn (eacl.exploration.public-schema-refinement-bridge/run-bridge!))
         (prn (eacl.exploration.sealed-plan-refinement-bridge/run-bridge!))
         (prn (eacl.exploration.cursor-refinement-bridge/run-bridge!))
         (prn (eacl.exploration.progress-checkpoint-refinement-bridge/run-bridge!)))'
  )
}

compile_tlc_family_runner() {
  if [ ! -f "$tlc_runner_class" ] || [ "$tlc_runner_source" -nt "$tlc_runner_class" ]; then
    mkdir -p "$tlc_runner_classes"
    javac -cp "$tla_jar" -d "$tlc_runner_classes" "$tlc_runner_source"
  fi
}

tlc_family() {
  family=$1
  shift
  run_root="$tlc_run_root/$family"
  log="$run_root/family.log"
  mkdir -p "$run_root/tmp" "$run_root/state"
  if java -Djava.io.tmpdir="$run_root/tmp" \
      -XX:+UseSerialGC -cp "$tla_jar:$tlc_runner_classes" TLCFamilyRunner \
      "$model_root" "$run_root/state" "$@" >"$log" 2>&1
  then
    sed -n '/^checked model:/p; /^killed mutation:/p' "$log"
  else
    status=$?
    sed -n '1,260p' "$log" >&2
    return "$status"
  fi
}

tlc_progress_checkpoint() {
  tlc_family ProgressCheckpoint \
    valid ProgressCheckpoint.cfg ProgressCheckpoint \
    mutant ProgressCheckpoint.partial.cfg ProgressCheckpoint \
    mutant ProgressCheckpoint.after-control.cfg ProgressCheckpoint \
    mutant ProgressCheckpoint.old-epoch.cfg ProgressCheckpoint \
    mutant ProgressCheckpoint.older.cfg ProgressCheckpoint \
    mutant ProgressCheckpoint.wrong-context.cfg ProgressCheckpoint \
    mutant ProgressCheckpoint.as-answer.cfg ProgressCheckpoint
}

tlc_atomic_attempt() {
  tlc_family AtomicAttempt \
    valid AtomicAttempt.cfg AtomicAttempt \
    mutant AtomicAttempt.integrate-partial.cfg AtomicAttempt \
    mutant AtomicAttempt.duplicate.cfg AtomicAttempt \
    mutant AtomicAttempt.publish-partial.cfg AtomicAttempt
}

compile_tlc_family_runner

dafny_check_one >"$tlc_run_root/dafny-one.log" 2>&1 &
dafny_one_pid=$!
dafny_check_two >"$tlc_run_root/dafny-two.log" 2>&1 &
dafny_two_pid=$!
dafny_check_three >"$tlc_run_root/dafny-three.log" 2>&1 &
dafny_three_pid=$!
dafny_check_four >"$tlc_run_root/dafny-four.log" 2>&1 &
dafny_four_pid=$!
source_refinement_check >"$tlc_run_root/source-refinement.log" 2>&1 &
source_refinement_pid=$!
tlc_progress_checkpoint &
tlc_progress_checkpoint_pid=$!
tlc_atomic_attempt &
tlc_atomic_attempt_pid=$!
dafny_one_failed=0
wait "$dafny_one_pid" || dafny_one_failed=1
dafny_two_failed=0
wait "$dafny_two_pid" || dafny_two_failed=1
dafny_three_failed=0
wait "$dafny_three_pid" || dafny_three_failed=1
dafny_four_failed=0
wait "$dafny_four_pid" || dafny_four_failed=1
source_refinement_failed=0
wait "$source_refinement_pid" || source_refinement_failed=1
tlc_progress_checkpoint_failed=0
wait "$tlc_progress_checkpoint_pid" || tlc_progress_checkpoint_failed=1
tlc_atomic_attempt_failed=0
wait "$tlc_atomic_attempt_pid" || tlc_atomic_attempt_failed=1
cat \
  "$tlc_run_root/dafny-one.log" \
  "$tlc_run_root/dafny-two.log" \
  "$tlc_run_root/dafny-three.log" \
  "$tlc_run_root/dafny-four.log"
cat "$tlc_run_root/source-refinement.log"
dafny_obligations=$(awk \
  '/Dafny program verifier finished with/ { total += $6 } END { print total + 0 }' \
  "$tlc_run_root/dafny-one.log" \
  "$tlc_run_root/dafny-two.log" \
  "$tlc_run_root/dafny-three.log" \
  "$tlc_run_root/dafny-four.log")
expected_dafny_obligations=541
dafny_count_failed=0
if [ "$dafny_obligations" -ne "$expected_dafny_obligations" ]; then
  printf 'expected %s Dafny obligations, observed %s\n' \
    "$expected_dafny_obligations" "$dafny_obligations" >&2
  dafny_count_failed=1
else
  printf 'Dafny aggregate: %s verified obligations\n' "$dafny_obligations"
fi
if [ "$tlc_progress_checkpoint_failed" -ne 0 ] || \
   [ "$tlc_atomic_attempt_failed" -ne 0 ] || \
   [ "$dafny_one_failed" -ne 0 ] || \
   [ "$dafny_two_failed" -ne 0 ] || \
   [ "$dafny_three_failed" -ne 0 ] || \
   [ "$dafny_four_failed" -ne 0 ] || \
   [ "$dafny_count_failed" -ne 0 ] || \
   [ "$source_refinement_failed" -ne 0 ]; then
  exit 1
fi

gate_finished_at=$(date +%s)
gate_elapsed_seconds=$((gate_finished_at - gate_started_at))
if [ "$gate_elapsed_seconds" -gt "$gate_hard_limit_seconds" ]; then
  printf 'formal gate exceeded hard iteration limit: %ss > %ss\n' \
    "$gate_elapsed_seconds" "$gate_hard_limit_seconds" >&2
  exit 1
fi
printf 'formal gate wall-time ceiling: %ss <= %ss\n' \
  "$gate_elapsed_seconds" "$gate_hard_limit_seconds"
