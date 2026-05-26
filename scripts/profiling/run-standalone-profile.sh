#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '> %s\n' "$*" >&2
}

require_dir() {
  [[ -d "$1" ]] || die "missing directory: $1"
}

require_file() {
  [[ -f "$1" ]] || die "missing file: $1"
}

sanitize_label() {
  printf '%s' "$1" | tr -c '[:alnum:]._-' '-'
}

mode_to_event() {
  case "$1" in
    cpu) printf 'cpu' ;;
    wall) printf 'wall' ;;
    alloc|allocation) printf 'alloc' ;;
    lock) printf 'lock' ;;
    *) return 1 ;;
  esac
}

write_config() {
  local mode_label="$1"
  local telemetry_file="$2"
  local mode_config_home="${config_home}/${mode_label}"
  mkdir -p "${mode_config_home}"

  cat >"${mode_config_home}/config.toml" <<EOF
[paths]
logsDir = "${logs_dir}/${mode_label}"
cacheDir = "${results_dir}/cache/${mode_label}"
descriptorDir = "${results_dir}/descriptors/${mode_label}"
socketDir = "${results_dir}/sockets/${mode_label}"

[gradle]
toolingApiTimeoutMillis = ${tooling_timeout_ms}
maxIncludedProjects = ${max_included_projects}

[telemetry]
enabled = true
scopes = "all"
detail = "${telemetry_detail}"
outputFile = "${telemetry_file}"

[indexing]
phase2Enabled = true
phase2Parallelism = ${max_concurrent_requests}
referenceBatchSize = 50

[profiling]
enabled = false
EOF
}

collect_jvm_diagnostics() {
  local pid="$1"
  local mode_label="$2"
  local stage="$3"
  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 0
  fi

  local stage_dir="${diagnostics_dir}/${mode_label}/${stage}"
  local mode_logs_dir="${logs_dir}/${mode_label}"
  mkdir -p "${stage_dir}" "${mode_logs_dir}"

  ps -o pid,ppid,rss,vsz,pcpu,pmem,etime,comm -p "${pid}" >"${stage_dir}/process.txt" 2>"${mode_logs_dir}/ps-${stage}.err" || true
  jcmd "${pid}" VM.uptime >"${stage_dir}/uptime.txt" 2>"${mode_logs_dir}/jcmd-uptime-${stage}.err" || true
  jcmd "${pid}" VM.command_line >"${stage_dir}/command-line.txt" 2>"${mode_logs_dir}/jcmd-command-line-${stage}.err" || true
  jcmd "${pid}" VM.flags >"${stage_dir}/vm-flags.txt" 2>"${mode_logs_dir}/jcmd-vm-flags-${stage}.err" || true
  jcmd "${pid}" VM.native_memory summary >"${stage_dir}/native-memory-summary.txt" 2>"${mode_logs_dir}/jcmd-native-memory-${stage}.err" || true
  jcmd "${pid}" GC.heap_info >"${stage_dir}/heap-info.txt" 2>"${mode_logs_dir}/jcmd-heap-info-${stage}.err" || true
  jcmd "${pid}" Thread.print >"${stage_dir}/thread-print.txt" 2>"${mode_logs_dir}/jcmd-thread-print-${stage}.err" || true
  jcmd "${pid}" VM.system_properties >"${stage_dir}/system-properties.txt" 2>"${mode_logs_dir}/jcmd-system-properties-${stage}.err" || true
  jcmd "${pid}" GC.class_histogram >"${stage_dir}/class-histogram.txt" 2>"${mode_logs_dir}/jcmd-class-histogram-${stage}.err" || true
}

start_jfr() {
  local pid="$1"
  local mode_label="$2"
  local jfr_name="$3"
  local jfr_file="$4"
  local mode_logs_dir="${logs_dir}/${mode_label}"
  mkdir -p "$(dirname "${jfr_file}")" "${mode_logs_dir}"

  jcmd "${pid}" JFR.start \
    "name=${jfr_name}" \
    settings=profile \
    disk=true \
    "filename=${jfr_file}" \
    >"${mode_logs_dir}/jcmd-jfr-start.log" \
    2>"${mode_logs_dir}/jcmd-jfr-start.err" || true
}

stop_jfr() {
  local pid="$1"
  local mode_label="$2"
  local jfr_name="$3"
  local jfr_file="$4"
  local mode_logs_dir="${logs_dir}/${mode_label}"

  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 0
  fi

  jcmd "${pid}" JFR.dump "name=${jfr_name}" "filename=${jfr_file}" \
    >"${mode_logs_dir}/jcmd-jfr-dump.log" \
    2>"${mode_logs_dir}/jcmd-jfr-dump.err" || true
  jcmd "${pid}" JFR.stop "name=${jfr_name}" \
    >"${mode_logs_dir}/jcmd-jfr-stop.log" \
    2>"${mode_logs_dir}/jcmd-jfr-stop.err" || true
}

shutdown_daemon() {
  if [[ -n "${daemon_pid:-}" ]] && kill -0 "${daemon_pid}" >/dev/null 2>&1; then
    kill "${daemon_pid}" >/dev/null 2>&1 || true
    wait "${daemon_pid}" >/dev/null 2>&1 || true
  fi
}

workspace_dir="/work/target"
results_dir="/work/results"
backend_launcher="/opt/kast/backend/kast-standalone"
duration="${KAST_PROFILE_DURATION:-45}"
profile_modes="${KAST_PROFILE_MODES:-wall,cpu}"
heap="${KAST_PROFILE_HEAP:-8g}"
max_concurrent_requests="${KAST_MAX_CONCURRENT_REQUESTS:-4}"
tooling_timeout_ms="${KAST_PROFILE_TOOLING_TIMEOUT_MS:-300000}"
max_included_projects="${KAST_PROFILE_MAX_INCLUDED_PROJECTS:-200}"
include_refresh="${KAST_PROFILE_INCLUDE_REFRESH:-false}"
target_label="${KAST_PROFILE_TARGET_LABEL:-target}"
ready_timeout_seconds="${KAST_PROFILE_READY_TIMEOUT_SECONDS:-600}"
attach_delay_seconds="${KAST_PROFILE_ATTACH_DELAY_SECONDS:-1}"
rpc_port="${KAST_PROFILE_RPC_PORT:-37645}"
telemetry_detail="${KAST_PROFILE_TELEMETRY_DETAIL:-verbose}"
native_memory_tracking="${KAST_PROFILE_NATIVE_MEMORY_TRACKING:-detail}"

require_dir "${workspace_dir}"
require_dir "${results_dir}"
require_file "${backend_launcher}"
command -v asprof >/dev/null 2>&1 || die "asprof is not on PATH"
command -v python3 >/dev/null 2>&1 || die "python3 is not on PATH"

logs_dir="${results_dir}/logs"
telemetry_dir="${results_dir}/telemetry"
profiling_dir="${results_dir}/profiling"
diagnostics_dir="${results_dir}/diagnostics"
jfr_dir="${results_dir}/jfr"
config_home="${results_dir}/kast-config"
home_dir="${HOME:-${results_dir}/home}"
mkdir -p "${logs_dir}" "${telemetry_dir}" "${profiling_dir}" "${diagnostics_dir}" "${jfr_dir}" "${config_home}" "${home_dir}"

export JAVA_OPTS="-Xmx${heap} -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+PreserveFramePointer -XX:NativeMemoryTracking=${native_memory_tracking} -XX:FlightRecorderOptions=stackdepth=256 ${JAVA_OPTS:-}"
export HOME="${home_dir}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/work/gradle-home}"

daemon_args=(
  "--workspace-root=${workspace_dir}"
  "--transport=tcp"
  "--tcp-host=127.0.0.1"
  "--tcp-port=${rpc_port}"
  "--request-timeout-ms=300000"
  "--max-results=2000"
  "--max-concurrent-requests=${max_concurrent_requests}"
)

trap shutdown_daemon EXIT

run_profile_mode() {
  local mode="$1"
  local profile_run_index="$2"
  local event
  event="$(mode_to_event "${mode}")" || die "unsupported profile mode: ${mode}"

  local mode_label
  mode_label="$(sanitize_label "${profile_run_index}-${mode}")"
  local mode_logs_dir="${logs_dir}/${mode_label}"
  local telemetry_file="${telemetry_dir}/standalone-spans-${mode_label}.jsonl"
  local output_file="${profiling_dir}/startup-workload-${mode_label}.html"
  local asprof_log_file="${mode_logs_dir}/asprof-${mode}.log"
  local asprof_exit_file="${mode_logs_dir}/asprof-${mode}.exit"
  local jfr_name="kast-profile-${mode_label}"
  local jfr_file="${jfr_dir}/startup-workload-${mode_label}.jfr"

  mkdir -p "${mode_logs_dir}"
  write_config "${mode_label}" "${telemetry_file}"
  export KAST_CONFIG_HOME="${config_home}/${mode_label}"

  local start_monotonic_ns
  start_monotonic_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
  log "Starting kast standalone for ${target_label} profile-mode=${mode}"
  "${backend_launcher}" "${daemon_args[@]}" \
    >"${mode_logs_dir}/kast-standalone.stdout.log" \
    2>"${mode_logs_dir}/kast-standalone.stderr.log" &
  daemon_pid="$!"

  sleep "${attach_delay_seconds}"
  start_jfr "${daemon_pid}" "${mode_label}" "${jfr_name}" "${jfr_file}"
  collect_jvm_diagnostics "${daemon_pid}" "${mode_label}" "startup"

  log "Starting async-profiler mode=${mode} event=${event} duration=${duration}s"
  (
    set +e
    local exit_code=1
    local attempt
    for attempt in 1 2 3 4 5; do
      asprof -d "${duration}" -e "${event}" -f "${output_file}" "${daemon_pid}" >"${asprof_log_file}" 2>&1
      exit_code=$?
      if [[ "${exit_code}" -eq 0 ]]; then
        break
      fi
      sleep 1
    done
    printf '%s\n' "${exit_code}" >"${asprof_exit_file}"
    exit "${exit_code}"
  ) &
  local profiler_pid="$!"

  local workload_status=0
  python3 /opt/kast-profile/kast_profile_workload.py \
    --host 127.0.0.1 \
    --port "${rpc_port}" \
    --workspace "${workspace_dir}" \
    --results "${results_dir}" \
    --target-label "${target_label}" \
    --ready-timeout-seconds "${ready_timeout_seconds}" \
    --start-monotonic-ns "${start_monotonic_ns}" \
    --include-refresh "${include_refresh}" \
    --profile-mode "${mode}" \
    --profile-run-index "${profile_run_index}" \
    || workload_status=$?

  collect_jvm_diagnostics "${daemon_pid}" "${mode_label}" "after-workload"

  local profiler_status=0
  if ! wait "${profiler_pid}"; then
    profiler_status=1
  fi

  stop_jfr "${daemon_pid}" "${mode_label}" "${jfr_name}" "${jfr_file}"
  collect_jvm_diagnostics "${daemon_pid}" "${mode_label}" "final"
  shutdown_daemon
  daemon_pid=""

  if [[ "${profiler_status}" -ne 0 ]]; then
    log "async-profiler mode=${mode} failed; see ${asprof_log_file}"
  fi

  if [[ "${workload_status}" -ne 0 || "${profiler_status}" -ne 0 ]]; then
    return 1
  fi
  return 0
}

session_status=0
profile_run_index=0
IFS=',' read -r -a requested_modes <<<"${profile_modes}"
for raw_mode in "${requested_modes[@]}"; do
  mode="$(printf '%s' "${raw_mode}" | tr '[:upper:]' '[:lower:]' | xargs)"
  [[ -n "${mode}" ]] || continue
  profile_run_index=$((profile_run_index + 1))
  if ! run_profile_mode "${mode}" "${profile_run_index}"; then
    session_status=1
  fi
done

exit "${session_status}"
