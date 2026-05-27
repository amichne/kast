#!/usr/bin/env bash
set -euo pipefail

runner="scripts/profiling/run-standalone-profile.sh"
workload="scripts/profiling/kast_profile_workload.py"

fail() {
  printf 'standalone-profile harness contract failed: %s\n' "$*" >&2
  exit 1
}

[[ -f "$runner" ]] || fail "missing $runner"
[[ -f "$workload" ]] || fail "missing $workload"

require_runner_text() {
  local needle="$1"
  grep -Fq -- "$needle" "$runner" || fail "runner missing required text: $needle"
}

require_workload_text() {
  local needle="$1"
  grep -Fq -- "$needle" "$workload" || fail "workload missing required text: $needle"
}

require_runner_text "run_profile_mode()"
require_runner_text "start_jfr()"
require_runner_text "stop_jfr()"
require_runner_text "write_gradle_profile_properties()"
require_runner_text "--profile-mode \"\${mode}\""
require_runner_text "--profile-run-index \"\${profile_run_index}\""
require_runner_text "JFR.start"
require_runner_text "JFR.dump"
require_runner_text "JFR.stop"
require_runner_text "org.gradle.jvmargs=\${gradle_jvmargs}"
require_runner_text "kotlin.daemon.jvmargs=\${kotlin_daemon_jvmargs}"
require_runner_text "org.gradle.workers.max=\${gradle_workers_max}"

require_workload_text "--profile-mode"
require_workload_text "--profile-run-index"
require_workload_text '"profileMode": args.profile_mode'
require_workload_text '"profileRunIndex": args.profile_run_index'
require_workload_text '"profileModes": sorted(profile_modes)'

if grep -Fq "profiler_pids" "$runner"; then
  fail "runner must not manage concurrent async-profiler pids"
fi
