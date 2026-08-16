#!/bin/sh
set -eu

leaf_started_at=$(date +%s)
leaf_hard_limit_seconds=5

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
model_root="$repo_root/target/exploration/stable-discovery"
dafny="$repo_root/target/formal-tools/dafny-4.11.0/dafny/dafny"
tla_jar="$repo_root/target/formal-tools/tla2tools-1.7.4.jar"
tlc_runner_source="$model_root/TLCFamilyRunner.java"
tlc_runner_classes="$model_root/tlc-runner-classes"
tlc_runner_class="$tlc_runner_classes/TLCFamilyRunner.class"

usage() {
  printf 'usage: sh verify-leaf.sh <Model.dfy|TlcFamily>\n' >&2
  printf 'TLC families: AtomicAttempt DescriptorCoalescing ProgressCheckpoint ReducerReadAhead ServiceLifecycle\n' >&2
  exit 2
}

[ "$#" -eq 1 ] || usage
leaf=$1

dafny_leaf() {
  case "$leaf" in
    */*)
      printf 'Dafny leaf must be a basename under %s\n' "$model_root" >&2
      exit 2
      ;;
  esac
  [ -f "$model_root/$leaf" ] || usage
  "$dafny" verify --verification-time-limit 15 "$model_root/$leaf"
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
  run_root="$model_root/tlc-leaf-runs/$$/$family"
  log="$run_root/family.log"
  mkdir -p "$run_root/tmp" "$run_root/state"
  compile_tlc_family_runner
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

case "$leaf" in
  *.dfy)
    dafny_leaf
    ;;
  AtomicAttempt)
    tlc_family AtomicAttempt \
      valid AtomicAttempt.cfg AtomicAttempt \
      mutant AtomicAttempt.integrate-partial.cfg AtomicAttempt \
      mutant AtomicAttempt.duplicate.cfg AtomicAttempt \
      mutant AtomicAttempt.publish-partial.cfg AtomicAttempt
    ;;
  DescriptorCoalescing)
    tlc_family DescriptorCoalescing \
      valid DescriptorCoalescing.cfg DescriptorCoalescing \
      mutant DescriptorCoalescing.duplicate-flight.cfg DescriptorCoalescing
    ;;
  ProgressCheckpoint)
    tlc_family ProgressCheckpoint \
      valid ProgressCheckpoint.cfg ProgressCheckpoint \
      mutant ProgressCheckpoint.partial.cfg ProgressCheckpoint \
      mutant ProgressCheckpoint.after-control.cfg ProgressCheckpoint \
      mutant ProgressCheckpoint.old-epoch.cfg ProgressCheckpoint \
      mutant ProgressCheckpoint.older.cfg ProgressCheckpoint \
      mutant ProgressCheckpoint.wrong-context.cfg ProgressCheckpoint \
      mutant ProgressCheckpoint.as-answer.cfg ProgressCheckpoint
    ;;
  ReducerReadAhead)
    tlc_family ReducerReadAhead \
      valid ReducerReadAhead.cfg ReducerReadAhead \
      mutant ReducerReadAhead.integrate-any.cfg ReducerReadAhead \
      mutant ReducerReadAhead.lend-reserve.cfg ReducerReadAhead \
      mutant ReducerReadAhead.after-cancel.cfg ReducerReadAhead \
      mutant ReducerReadAhead.free-ready.cfg ReducerReadAhead \
      mutant ReducerReadAhead.evict-pinned.cfg ReducerReadAhead
    ;;
  ServiceLifecycle)
    tlc_family ServiceLifecycle \
      valid ServiceLifecycle.cfg ServiceLifecycle \
      mutant ServiceLifecycle.free-early.cfg ServiceLifecycle \
      mutant ServiceLifecycle.publish-late.cfg ServiceLifecycle \
      mutant ServiceLifecycle.ignore-retained.cfg ServiceLifecycle \
      mutant ServiceLifecycle.free-pinned.cfg ServiceLifecycle
    ;;
  *)
    usage
    ;;
esac

leaf_finished_at=$(date +%s)
leaf_elapsed_seconds=$((leaf_finished_at - leaf_started_at))
if [ "$leaf_elapsed_seconds" -gt "$leaf_hard_limit_seconds" ]; then
  printf 'formal leaf exceeded hard iteration limit: %ss > %ss\n' \
    "$leaf_elapsed_seconds" "$leaf_hard_limit_seconds" >&2
  exit 1
fi
printf 'formal leaf wall-time ceiling: %ss <= %ss\n' \
  "$leaf_elapsed_seconds" "$leaf_hard_limit_seconds"
