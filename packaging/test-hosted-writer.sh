#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "hosted-writer installed acceptance: $*" >&2
  exit 1
}

for command in jq osascript shasum sqlite3 unzip; do
  command -v "${command}" >/dev/null 2>&1 || fail "required command is unavailable: ${command}"
done

cli=${KAST_INSTALLED_PRODUCT:?KAST_INSTALLED_PRODUCT is required}/bin/kast
plugin_zip=${KAST_HOSTED_WRITER_PLUGIN_ZIP:?KAST_HOSTED_WRITER_PLUGIN_ZIP is required}
fixture=${KAST_HOSTED_WRITER_FIXTURE:?KAST_HOSTED_WRITER_FIXTURE is required}
idea=${KAST_HOSTED_WRITER_IDEA_EXECUTABLE:?KAST_HOSTED_WRITER_IDEA_EXECUTABLE is required}
candidate=${KAST_HOSTED_WRITER_ACCEPTANCE_CANDIDATE:?KAST_HOSTED_WRITER_ACCEPTANCE_CANDIDATE is required}
run_parent=${KAST_HOSTED_WRITER_RUN_PARENT:?KAST_HOSTED_WRITER_RUN_PARENT is required}

test -x "${cli}" || fail "installed CLI is not executable: ${cli}"
test -f "${plugin_zip}" || fail "packaged plugin is unavailable: ${plugin_zip}"
test -d "${fixture}" || fail "fixture is unavailable: ${fixture}"
test -x "${idea}" || fail "installed IDEA executable is unavailable: ${idea}"

mkdir -p "${run_parent}"
run_directory=$(mktemp -d "${run_parent}/hosted-writer.XXXXXX")
mkdir -p \
  "${run_directory}/config" \
  "${run_directory}/system" \
  "${run_directory}/plugins" \
  "${run_directory}/log" \
  "${run_directory}/observations" \
  "${run_directory}/retired-state"

bootstrap_config=${KAST_HOSTED_WRITER_IDEA_BOOTSTRAP_CONFIG:-${HOME}/Library/Application Support/JetBrains/IntelliJIdea2026.2}
if test -f "${bootstrap_config}/app-internal-state.db"; then
  cp "${bootstrap_config}/app-internal-state.db" "${run_directory}/config/app-internal-state.db"
fi
if test -f "${bootstrap_config}/options/ide.general.xml"; then
  mkdir -p "${run_directory}/config/options"
  cp "${bootstrap_config}/options/ide.general.xml" "${run_directory}/config/options/ide.general.xml"
fi

unzip -q "${plugin_zip}" -d "${run_directory}/plugins"
cat >"${run_directory}/idea.properties" <<EOF
idea.config.path=${run_directory}/config
idea.system.path=${run_directory}/system
idea.plugins.path=${run_directory}/plugins
idea.log.path=${run_directory}/log
idea.no.platform.update=true
idea.auto.reload.plugins=false
idea.trust.all.projects=true
idea.initially.ask.config=false
idea.show.tips.on.startup.default.value=false
idea.settings.sync.disabled=true
EOF

idea_home=$(cd "$(dirname "${idea}")/.." && pwd -P)
idea_vm_source=${KAST_HOSTED_WRITER_IDEA_VM_OPTIONS:-${idea_home}/bin/idea.vmoptions}
test -f "${idea_vm_source}" || fail "IDEA VM options are unavailable: ${idea_vm_source}"
cp "${idea_vm_source}" "${run_directory}/idea.vmoptions"
printf '\n-Dide.experimental.ui.onboarding=false\n' >>"${run_directory}/idea.vmoptions"

positive_jsonl=${run_directory}/positive.jsonl
negative_jsonl=${run_directory}/negative.jsonl
: >"${positive_jsonl}"
: >"${negative_jsonl}"

idea_pid=
current_root=
recovery_acl_target=
recovery_database=
application_fault_database=
fault_apply_pid=

root_digest() {
  printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
}

endpoint_directory() {
  local digest
  digest=$(root_digest "$1")
  printf '/tmp/.k%s' "${digest:0:24}"
}

state_directory() {
  printf '%s/.local/share/kast/state/workspaces/%s' "${HOME}" "$(root_digest "$1")"
}

new_workspace() {
  local name=$1
  local workspace=${run_directory}/${name}
  mkdir -p "${workspace}"
  cp -R "${fixture}/." "${workspace}"
  printf '%s' "${workspace}"
}

wait_for_endpoint() {
  local root=$1
  local endpoint
  endpoint=$(endpoint_directory "${root}")
  for _ in {1..90}; do
    if test -S "${endpoint}/s" && test -f "${endpoint}/s.endpoint.json"; then
      local descriptor_pid
      descriptor_pid=$(jq -r .processId "${endpoint}/s.endpoint.json")
      if test -n "${descriptor_pid}" && ps -p "${descriptor_pid}" >/dev/null 2>&1; then
        idea_pid=${descriptor_pid}
        return 0
      fi
    fi
    sleep 1
  done
  tail -200 "${run_directory}/log/idea.log" >&2 || true
  fail "endpoint did not publish for ${root}"
}

open_project() {
  local root=$1
  current_root=${root}
  if test -z "${idea_pid}" || ! ps -p "${idea_pid}" >/dev/null 2>&1; then
    IDEA_PROPERTIES="${run_directory}/idea.properties" \
      IDEA_VM_OPTIONS="${run_directory}/idea.vmoptions" \
      "${idea}" "${root}" >>"${run_directory}/idea.stdout" 2>&1 &
    idea_pid=$!
  else
    IDEA_PROPERTIES="${run_directory}/idea.properties" \
      IDEA_VM_OPTIONS="${run_directory}/idea.vmoptions" \
      "${idea}" "${root}" >>"${run_directory}/idea.stdout" 2>&1
  fi
  wait_for_endpoint "${root}"
}

open_project_expect_rejection() {
  local root=$1
  local line_count
  line_count=$(wc -l <"${run_directory}/log/idea.log")
  current_root=${root}
  IDEA_PROPERTIES="${run_directory}/idea.properties" \
    IDEA_VM_OPTIONS="${run_directory}/idea.vmoptions" \
    "${idea}" "${root}" >>"${run_directory}/idea.stdout" 2>&1
  for _ in {1..60}; do
    if tail -n "+$((line_count + 1))" "${run_directory}/log/idea.log" | \
      grep -q 'Kast hosted endpoint startup outcome: Rejected'; then
      tail -n "+$((line_count + 1))" "${run_directory}/log/idea.log" | \
        grep 'Kast hosted endpoint startup outcome: Rejected' | tail -1
      return 0
    fi
    sleep 1
  done
  tail -200 "${run_directory}/log/idea.log" >&2 || true
  fail "endpoint did not fail closed for corrupt state at ${root}"
}

close_project() {
  if test -z "${idea_pid}" || ! ps -p "${idea_pid}" >/dev/null 2>&1; then
    current_root=
    return 0
  fi
  osascript -e "tell application \"System Events\" to tell (first application process whose unix id is ${idea_pid}) to click menu item \"Close Project\" of menu \"File\" of menu bar 1" >/dev/null
  if test -n "${current_root}"; then
    local endpoint
    endpoint=$(endpoint_directory "${current_root}")
    for _ in {1..30}; do
      if ! test -e "${endpoint}"; then
        sleep 2
        current_root=
        return 0
      fi
      sleep 1
    done
    fail "endpoint namespace was not retired for ${current_root}"
  fi
}

shutdown_idea() {
  if test -n "${idea_pid}" && ps -p "${idea_pid}" >/dev/null 2>&1; then
    if test -n "${current_root}"; then
      close_project || true
    fi
    kill -TERM "${idea_pid}" >/dev/null 2>&1 || true
    for _ in {1..20}; do
      if ! ps -p "${idea_pid}" >/dev/null 2>&1; then break; fi
      sleep 1
    done
  fi
}

retire_state() {
  local root=$1
  local state
  state=$(state_directory "${root}")
  if test -e "${state}"; then
    mv "${state}" "${run_directory}/retired-state/$(basename "${state}")"
  fi
}

cleanup() {
  if test -n "${recovery_acl_target}" && test -e "${recovery_acl_target}"; then
    chmod -N "${recovery_acl_target}" || true
  fi
  if test -n "${fault_apply_pid}" && kill -0 "${fault_apply_pid}" >/dev/null 2>&1; then
    kill -TERM "${fault_apply_pid}" >/dev/null 2>&1 || true
    wait "${fault_apply_pid}" >/dev/null 2>&1 || true
  fi
  if test -n "${recovery_database}" && test -e "${recovery_database}"; then
    sqlite3 "${recovery_database}" 'DROP TRIGGER IF EXISTS kast_installed_save_fault;' || true
  fi
  if test -n "${application_fault_database}" && test -e "${application_fault_database}"; then
    sqlite3 "${application_fault_database}" \
      'DROP TRIGGER IF EXISTS kast_installed_application_fault;' || true
  fi
  shutdown_idea
}
trap cleanup EXIT

run_cli() {
  local root=$1
  local output=$2
  shift 2
  (cd "${root}" && "${cli}" "$@") >"${output}"
  if ! jq -e '.status == "complete"' "${output}" >/dev/null 2>&1; then
    cat "${output}" >&2
    fail "command did not complete: $*"
  fi
}

run_cli_qualified() {
  local root=$1
  local output=$2
  local expected=$3
  shift 3
  (cd "${root}" && "${cli}" "$@") >"${output}"
  if ! jq -e --arg expected "${expected}" \
    '.status == "qualified" and .qualification == $expected' "${output}" >/dev/null 2>&1; then
    cat "${output}" >&2
    fail "command did not report qualified ${expected}: $*"
  fi
}

run_cli_rejected() {
  local root=$1
  local output=$2
  local expected=$3
  shift 3
  set +e
  (cd "${root}" && "${cli}" "$@") >"${output}" 2>&1
  set -e
  if ! jq -e --arg expected "${expected}" \
    '.status == "rejected" and .reason == $expected' "${output}" >/dev/null 2>&1; then
    cat "${output}" >&2
    fail "command did not report ${expected}: $*"
  fi
}

assert_compiler_evidence() {
  local output=$1
  if ! jq -e '
    .status == "complete" and
    (.symbol.compilerEvidence.identity |
      type == "string" and startswith("canonical-signature-sha256-v1|")) and
    (.symbol.compilerEvidence.signature |
      .type == "function" and
      (.qualifiedIdentity | type == "string" and length > 0) and
      (.receiver.type == "absent" or
        (.receiver.type == "present" and
          (.receiver.compilerType | type == "string" and length > 0))) and
      (.contextReceivers | type == "array") and
      (.valueParameters | type == "array") and
      (.typeParameterCount | type == "number"))
  ' "${output}" >/dev/null 2>&1; then
    cat "${output}" >&2
    fail "symbol description did not expose structured compiler-grounded evidence"
  fi
}

run_cli_with_save_fault() {
  local root=$1
  local output=$2
  local expected=$3
  shift 3
  recovery_database=$(state_directory "${root}")/mutation.sqlite
  recovery_acl_target=$(target_file "${root}")
  sqlite3 "${recovery_database}" <<'SQL'
CREATE TRIGGER kast_installed_save_fault
BEFORE INSERT ON mutation_recovery_applied_write
BEGIN
  SELECT count(*) FROM (
    WITH RECURSIVE delay(value) AS (
      VALUES(0)
      UNION ALL
      SELECT value + 1 FROM delay WHERE value < 100000000
    )
    SELECT value FROM delay
  );
END;
SQL
  set +e
  (cd "${root}" && "${cli}" "$@") >"${output}" 2>&1 &
  fault_apply_pid=$!
  set -e

  local applied_transition_observed=false
  for _ in {1..400}; do
    if ! kill -0 "${fault_apply_pid}" >/dev/null 2>&1; then break; fi
    local stage
    stage=$(sqlite3 "${recovery_database}" \
      "SELECT stage FROM mutation_recovery WHERE stage = 'PRE_WRITE_DURABLE' LIMIT 1;" \
      2>/dev/null || true)
    if test "${stage}" = PRE_WRITE_DURABLE && ! sqlite3 "${recovery_database}" \
      'PRAGMA busy_timeout=0; BEGIN IMMEDIATE; ROLLBACK;' >/dev/null 2>&1; then
      applied_transition_observed=true
      break
    fi
    sleep 0.05
  done
  test "${applied_transition_observed}" = true || {
    cat "${output}" >&2
    fail "did not observe the durable applied transition before save"
  }
  chmod +a "$(id -un) deny read" "${recovery_acl_target}"

  set +e
  wait "${fault_apply_pid}"
  set -e
  fault_apply_pid=
  sqlite3 "${recovery_database}" 'DROP TRIGGER kast_installed_save_fault;'
  if ! jq -e --arg expected "${expected}" \
    '.status == "rejected" and .reason == $expected' "${output}" >/dev/null 2>&1; then
    cat "${output}" >&2
    fail "command did not report ${expected}: $*"
  fi
}

record_observation() {
  local destination=$1
  local name=$2
  local outcome=$3
  local artifact=$4
  local digest
  digest=$(shasum -a 256 "${artifact}" | awk '{print $1}')
  jq -nc \
    --arg name "${name}" \
    --arg outcome "${outcome}" \
    --arg digest "${digest}" \
    '{name:$name,outcome:$outcome,artifactDigest:$digest}' >>"${destination}"
}

capture_restart() {
  local root=$1
  local output=$2
  local endpoint
  endpoint=$(endpoint_directory "${root}")
  jq . "${endpoint}/s.endpoint.json" >"${output}"
  grep 'Kast hosted endpoint startup outcome: Prepared' "${run_directory}/log/idea.log" | \
    tail -1 >>"${output}"
}

target_file() {
  printf '%s/domains/alpha/one/src/main/kotlin/enterprise/alpha/one/Enterprise.kt' "$1"
}

resolve_target() {
  local root=$1
  local prefix=$2
  for _ in {1..120}; do
    if ! (cd "${root}" && "${cli}" symbol discover --mode name \
      --query enterpriseRootOperation --kind symbol --match exact-name --limit 10) \
      >"${prefix}.discover.json"; then
      if jq -e '.status == "rejected" and .reason == "workspace-not-ready"' \
        "${prefix}.discover.json" >/dev/null 2>&1; then
        sleep 0.25
        continue
      fi
      cat "${prefix}.discover.json" >&2
      fail "symbol discovery failed before refinement for ${root}"
    fi
    local candidate_selector
    candidate_selector=$(jq -r '.items[0].candidateSelector // empty' "${prefix}.discover.json")
    if test -n "${candidate_selector}"; then
      if ! (cd "${root}" && "${cli}" symbol resolve --candidate "${candidate_selector}") \
        >"${prefix}.resolve.json"; then
        if jq -e '
          .status == "rejected" and
          (.reason == "workspace-not-ready" or .reason == "candidate-stale")
        ' "${prefix}.resolve.json" >/dev/null 2>&1; then
          sleep 0.25
          continue
        fi
        cat "${prefix}.resolve.json" >&2
        fail "symbol refinement failed for ${root}"
      fi
      if jq -e '.status == "complete" and (.exactSelector | type == "string")' \
        "${prefix}.resolve.json" >/dev/null; then
        jq -er '.exactSelector' "${prefix}.resolve.json"
        return 0
      fi
    fi
    sleep 0.25
  done
  cat "${prefix}.discover.json" "${prefix}.resolve.json" >&2
  fail "symbol refinement did not stabilize for ${root}"
}

positive_root=$(new_workspace positive-workspace)
open_project "${positive_root}"
run_cli "${positive_root}" "${run_directory}/observations/positive-inspect.json" workspace inspect
record_observation "${positive_jsonl}" workspace.inspect COMPLETE "${run_directory}/observations/positive-inspect.json"

positive_selector=$(resolve_target "${positive_root}" "${run_directory}/observations/positive")
record_observation "${positive_jsonl}" symbol.resolve COMPLETE "${run_directory}/observations/positive.resolve.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-description.json" \
  symbol describe --selector "${positive_selector}"
assert_compiler_evidence "${run_directory}/observations/positive-description.json"
positive_compiler_evidence=$(jq -cS '.symbol.compilerEvidence' \
  "${run_directory}/observations/positive-description.json")
record_observation "${positive_jsonl}" symbol.describe.compiler-evidence COMPLETE \
  "${run_directory}/observations/positive-description.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-topology.json" topology build
record_observation "${positive_jsonl}" topology.build COMPLETE "${run_directory}/observations/positive-topology.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-traversal.json" \
  traversal run --selector "${positive_selector}" --relation references --maximum-depth 3 --maximum-results 50
record_observation "${positive_jsonl}" traversal.run COMPLETE "${run_directory}/observations/positive-traversal.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-plan.json" \
  change plan --intent add-declaration --target "${positive_selector}" \
  --declaration 'fun hostedWriterAcceptanceAdded(): String = "installed"'
positive_plan=$(jq -er .planIdentity "${run_directory}/observations/positive-plan.json")
record_observation "${positive_jsonl}" change.plan COMPLETE "${run_directory}/observations/positive-plan.json"

run_cli "${positive_root}" "${run_directory}/observations/positive-apply.json" change apply --plan "${positive_plan}"
positive_application=$(jq -er .applicationIdentity "${run_directory}/observations/positive-apply.json")
record_observation "${positive_jsonl}" change.apply COMPLETE "${run_directory}/observations/positive-apply.json"
run_cli_rejected "${positive_root}" "${run_directory}/observations/stale-selector.json" \
  selector-stale traversal run --selector "${positive_selector}" --relation references \
  --maximum-depth 3 --maximum-results 50
record_observation "${negative_jsonl}" stale-selector-after-live-publication REJECTED \
  "${run_directory}/observations/stale-selector.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-verify.json" \
  change verify --application "${positive_application}"
record_observation "${positive_jsonl}" change.verify COMPLETE "${run_directory}/observations/positive-verify.json"
positive_result_selector=$(resolve_target "${positive_root}" "${run_directory}/observations/positive-result")
run_cli "${positive_root}" "${run_directory}/observations/positive-result-traversal.json" \
  traversal run --selector "${positive_result_selector}" --relation references \
  --maximum-depth 3 --maximum-results 50
record_observation "${positive_jsonl}" traversal.run.resulting-generation COMPLETE \
  "${run_directory}/observations/positive-result-traversal.json"
positive_pre_restart_remainder=${positive_result_selector#exact:v1:}
positive_pre_restart_generation=${positive_pre_restart_remainder%%:*}
[[ "${positive_pre_restart_generation}" =~ ^[0-9]+$ ]] || \
  fail "resulting selector did not retain a numeric generation"

close_project
open_project "${positive_root}"
capture_restart "${positive_root}" "${run_directory}/observations/restart-after-lifecycle.json"
record_observation "${positive_jsonl}" restart.after-lifecycle COMPLETE \
  "${run_directory}/observations/restart-after-lifecycle.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-restart-inspect.json" \
  workspace inspect
run_cli_rejected "${positive_root}" "${run_directory}/observations/restart-stale-selector.json" \
  selector-stale traversal run --selector "${positive_result_selector}" --relation references \
  --maximum-depth 3 --maximum-results 50
record_observation "${negative_jsonl}" stale-selector-after-cold-restart REJECTED \
  "${run_directory}/observations/restart-stale-selector.json"
positive_restart_selector=$(resolve_target "${positive_root}" \
  "${run_directory}/observations/positive-restart")
positive_restart_remainder=${positive_restart_selector#exact:v1:}
positive_restart_generation=${positive_restart_remainder%%:*}
[[ "${positive_restart_generation}" =~ ^[0-9]+$ ]] || \
  fail "restart selector did not retain a numeric generation"
if test "${positive_restart_generation}" -le "${positive_pre_restart_generation}"; then
  fail "cold project restart did not conservatively advance semantic generation"
fi
run_cli "${positive_root}" "${run_directory}/observations/positive-restart-description.json" \
  symbol describe --selector "${positive_restart_selector}"
assert_compiler_evidence "${run_directory}/observations/positive-restart-description.json"
positive_restart_compiler_evidence=$(jq -cS '.symbol.compilerEvidence' \
  "${run_directory}/observations/positive-restart-description.json")
if test "${positive_restart_compiler_evidence}" != "${positive_compiler_evidence}"; then
  cat "${run_directory}/observations/positive-description.json" >&2
  cat "${run_directory}/observations/positive-restart-description.json" >&2
  fail "compiler-grounded evidence changed across an unchanged symbol restart"
fi
record_observation "${positive_jsonl}" symbol.describe.compiler-evidence.after-restart COMPLETE \
  "${run_directory}/observations/positive-restart-description.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-restart-topology.json" \
  topology build
if ! jq -e '.snapshotStatus == "reused"' \
  "${run_directory}/observations/positive-restart-topology.json" >/dev/null 2>&1; then
  cat "${run_directory}/observations/positive-restart-topology.json" >&2
  fail "unchanged durable topology was not rebound after cold restart"
fi
record_observation "${positive_jsonl}" topology.build.after-restart REUSED \
  "${run_directory}/observations/positive-restart-topology.json"
run_cli "${positive_root}" "${run_directory}/observations/positive-durable-traversal.json" \
  traversal run --selector "${positive_restart_selector}" --relation references \
  --maximum-depth 3 --maximum-results 50
record_observation "${positive_jsonl}" traversal.run.durable COMPLETE "${run_directory}/observations/positive-durable-traversal.json"
close_project

negative_root=$(new_workspace negative-workspace)
different_root=$(new_workspace different-root-workspace)
open_project "${negative_root}"
negative_selector=$(resolve_target "${negative_root}" "${run_directory}/observations/negative")
run_cli_rejected "${negative_root}" "${run_directory}/observations/plan-before-topology.json" \
  topology-build-required change plan --intent add-declaration --target "${negative_selector}" \
  --declaration 'fun shouldRequireTopology(): Unit = Unit'
record_observation "${negative_jsonl}" plan-before-topology-build REJECTED "${run_directory}/observations/plan-before-topology.json"
run_cli_rejected "${different_root}" "${run_directory}/observations/different-root.json" \
  ide-descriptor-read-rejected workspace inspect
record_observation "${negative_jsonl}" different-root REJECTED "${run_directory}/observations/different-root.json"

run_cli "${negative_root}" "${run_directory}/observations/negative-topology.json" topology build
run_cli "${negative_root}" "${run_directory}/observations/negative-plan.json" \
  change plan --intent add-declaration --target "${negative_selector}" \
  --declaration 'fun changedBeforeApply(): Unit = Unit'
negative_plan=$(jq -er .planIdentity "${run_directory}/observations/negative-plan.json")
printf '\n// externally changed after planning\n' >>"$(target_file "${negative_root}")"
sleep 3
run_cli_rejected "${negative_root}" "${run_directory}/observations/source-changed.json" \
  content-changed change apply --plan "${negative_plan}"
record_observation "${negative_jsonl}" source-changed-between-plan-and-apply REJECTED "${run_directory}/observations/source-changed.json"
run_cli_rejected "${negative_root}" "${run_directory}/observations/stale-plan-selector.json" \
  symbol-resolve-required change plan --intent add-declaration --target "${negative_selector}" \
  --declaration 'fun staleSelector(): Unit = Unit'
record_observation "${negative_jsonl}" stale-generation-selector REJECTED "${run_directory}/observations/stale-plan-selector.json"
close_project

negative_state=$(state_directory "${negative_root}")
printf 'corrupt-topology' >"${negative_state}/topology.sqlite"
open_project_expect_rejection "${negative_root}" >"${run_directory}/observations/corrupt-topology.json"
record_observation "${negative_jsonl}" corrupt-topology-database REJECTED "${run_directory}/observations/corrupt-topology.json"
close_project

authority_root=$(new_workspace corrupt-authority-workspace)
open_project "${authority_root}"
authority_selector=$(resolve_target "${authority_root}" "${run_directory}/observations/authority")
run_cli "${authority_root}" "${run_directory}/observations/authority-topology.json" topology build
run_cli "${authority_root}" "${run_directory}/observations/authority-plan.json" \
  change plan --intent add-declaration --target "${authority_selector}" \
  --declaration 'fun corruptAuthority(): Unit = Unit'
authority_plan=$(jq -er .planIdentity "${run_directory}/observations/authority-plan.json")
close_project
authority_state=$(state_directory "${authority_root}")
sqlite3 "${authority_state}/mutation.sqlite" \
  "UPDATE hosted_change_plan SET document_sha256 = printf('%064d', 0) WHERE identity = '${authority_plan}';"
open_project "${authority_root}"
run_cli_rejected "${authority_root}" "${run_directory}/observations/corrupt-authority.json" \
  recovery-required change apply --plan "${authority_plan}"
record_observation "${negative_jsonl}" corrupt-change-authority-record REJECTED "${run_directory}/observations/corrupt-authority.json"
close_project

successful_recovery_root=$(new_workspace successful-recovery-workspace)
open_project "${successful_recovery_root}"
successful_recovery_selector=$(resolve_target \
  "${successful_recovery_root}" "${run_directory}/observations/successful-recovery")
run_cli "${successful_recovery_root}" \
  "${run_directory}/observations/successful-recovery-topology.json" topology build
run_cli "${successful_recovery_root}" \
  "${run_directory}/observations/successful-recovery-plan.json" \
  change plan --intent add-declaration --target "${successful_recovery_selector}" \
  --declaration 'fun rollsBackAfterAuthorityFailure(): Unit = Unit'
successful_recovery_plan=$(jq -er .planIdentity \
  "${run_directory}/observations/successful-recovery-plan.json")
application_fault_database=$(state_directory "${successful_recovery_root}")/mutation.sqlite
sqlite3 "${application_fault_database}" <<'SQL'
CREATE TRIGGER kast_installed_application_fault
BEFORE INSERT ON hosted_change_application
BEGIN
  SELECT RAISE(ABORT, 'forced application authority failure');
END;
SQL
run_cli_rejected "${successful_recovery_root}" \
  "${run_directory}/observations/application-authority-failure.json" \
  recovery-required change apply --plan "${successful_recovery_plan}"
grep -q 'rollsBackAfterAuthorityFailure' "$(target_file "${successful_recovery_root}")" || \
  fail "application authority fault did not follow a physical write"
run_cli_rejected "${successful_recovery_root}" \
  "${run_directory}/observations/post-write-failure-plan.json" \
  recovery-required change plan --intent add-declaration --target "${successful_recovery_selector}" \
  --declaration 'fun mustNotPlanAfterAuthorityFailure(): Unit = Unit'
run_cli_rejected "${successful_recovery_root}" \
  "${run_directory}/observations/post-write-failure-apply.json" \
  recovery-required change apply --plan "${successful_recovery_plan}"
run_cli_rejected "${successful_recovery_root}" \
  "${run_directory}/observations/post-write-failure-verify.json" \
  obligation-failed change verify --application "application:$(printf '0%.0s' {1..64})"
cat "${run_directory}/observations/post-write-failure-apply.json" \
  "${run_directory}/observations/post-write-failure-verify.json" >> \
  "${run_directory}/observations/post-write-failure-plan.json"
record_observation "${negative_jsonl}" post-write-authority-failure-withdraws-writer REJECTED \
  "${run_directory}/observations/post-write-failure-plan.json"
sqlite3 "${application_fault_database}" \
  'DROP TRIGGER kast_installed_application_fault;'
application_fault_database=
run_cli "${successful_recovery_root}" \
  "${run_directory}/observations/successful-live-recover.json" \
  change recover --plan "${successful_recovery_plan}"
if grep -q 'rollsBackAfterAuthorityFailure' "$(target_file "${successful_recovery_root}")"; then
  fail "successful recovery did not restore the prior source"
fi
record_observation "${positive_jsonl}" change.recover.live COMPLETE \
  "${run_directory}/observations/successful-live-recover.json"
successful_recovery_result_selector=$(resolve_target \
  "${successful_recovery_root}" "${run_directory}/observations/successful-recovery-result")
run_cli "${successful_recovery_root}" \
  "${run_directory}/observations/successful-recovery-result-topology.json" topology build
run_cli "${successful_recovery_root}" \
  "${run_directory}/observations/plan-after-live-recovery.json" \
  change plan --intent add-declaration --target "${successful_recovery_result_selector}" \
  --declaration 'fun plannedAfterLiveRecovery(): Unit = Unit'
record_observation "${positive_jsonl}" change.plan.after-live-recovery COMPLETE \
  "${run_directory}/observations/plan-after-live-recovery.json"
close_project

recovery_root=$(new_workspace recovery-workspace)
open_project "${recovery_root}"
recovery_selector=$(resolve_target "${recovery_root}" "${run_directory}/observations/recovery")
run_cli "${recovery_root}" "${run_directory}/observations/recovery-topology.json" topology build
run_cli "${recovery_root}" "${run_directory}/observations/recovery-plan.json" \
  change plan --intent add-declaration --target "${recovery_selector}" \
  --declaration 'fun forcedSaveFailure(): Unit = Unit'
recovery_plan=$(jq -er .planIdentity "${run_directory}/observations/recovery-plan.json")
run_cli_with_save_fault "${recovery_root}" "${run_directory}/observations/forced-save-failure.json" \
  recovery-required change apply --plan "${recovery_plan}"
run_cli_rejected "${recovery_root}" "${run_directory}/observations/unresolved-recovery-plan.json" \
  recovery-required change plan --intent add-declaration --target "${recovery_selector}" \
  --declaration 'fun mustNotPlanDuringRecovery(): Unit = Unit'
run_cli_rejected "${recovery_root}" "${run_directory}/observations/unresolved-recovery-apply.json" \
  recovery-required change apply --plan "${recovery_plan}"
cat "${run_directory}/observations/unresolved-recovery-apply.json" >> \
  "${run_directory}/observations/unresolved-recovery-plan.json"
record_observation "${negative_jsonl}" unresolved-recovery-blocks-plan-and-apply REJECTED \
  "${run_directory}/observations/unresolved-recovery-plan.json"
chmod -N "${recovery_acl_target}"
recovery_acl_target=
recovery_database=
close_project
open_project "${recovery_root}"
run_cli_qualified "${recovery_root}" "${run_directory}/observations/recover-after-restart.json" \
  manual-recovery-required \
  change recover --plan "${recovery_plan}"
cat "${run_directory}/observations/recover-after-restart.json" >> \
  "${run_directory}/observations/forced-save-failure.json"
record_observation "${negative_jsonl}" forced-save-failure-restart-recover REJECTED \
  "${run_directory}/observations/forced-save-failure.json"
close_project

shutdown_idea
for root in \
  "${positive_root}" \
  "${negative_root}" \
  "${authority_root}" \
  "${successful_recovery_root}" \
  "${recovery_root}"; do
  retire_state "${root}"
done

repository_head=$(git -C "${KAST_PROJECT_ROOT:?KAST_PROJECT_ROOT is required}" rev-parse HEAD)
mkdir -p "$(dirname "${candidate}")"
jq -n \
  --arg repository_head "${repository_head}" \
  --slurpfile positive "${positive_jsonl}" \
  --slurpfile negative "${negative_jsonl}" \
  '{schemaVersion:1,repositoryHead:$repository_head,positiveJourney:$positive,negativeJourneys:$negative}' \
  >"${candidate}"

trap - EXIT
echo "hosted-writer installed acceptance complete: ${candidate}"
