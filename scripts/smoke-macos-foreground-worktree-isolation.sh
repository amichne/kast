#!/usr/bin/env bash
set -Eeuo pipefail

script_name="${0##*/}"
mode="${1:-}"
repo_root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
python_checks="$repo_root/scripts/smoke_macos_foreground_worktree_checks.py"
process_checks="$repo_root/scripts/smoke_macos_fixture_processes.py"
process_checks_test="$repo_root/scripts/smoke_macos_fixture_processes_test.py"
idea_app="${KAST_SMOKE_IDEA_APP:-/Users/amichne/Applications/IntelliJ IDEA.app}"
scratch=""
temp_root="$(cd -- "${TMPDIR:-/tmp}" && pwd -P)"

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'usage: %s --self-test|--preflight-only|--run-foreground\n' "$script_name" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

preflight() {
  [[ "$(uname -s)" == "Darwin" ]] || fail "this smoke requires macOS"
  for command in git python3 ps find mktemp; do
    require_command "$command"
  done
  [[ -x "$repo_root/gradlew" ]] || fail "Gradle wrapper is unavailable"
  [[ -f "$python_checks" ]] || fail "smoke assertions are unavailable: $python_checks"
  [[ -f "$process_checks" ]] || fail "smoke process guard is unavailable: $process_checks"
  [[ -f "$process_checks_test" ]] || fail "smoke process tests are unavailable: $process_checks_test"
  [[ -d "$idea_app" ]] || fail "IntelliJ IDEA application is unavailable: $idea_app"
  idea_app="$(cd -- "$idea_app" && pwd -P)"
  idea_executable="$idea_app/Contents/MacOS/idea"
  idea_vm_options="$idea_app/Contents/bin/idea.vmoptions"
  [[ -x "$idea_executable" ]] || fail "IntelliJ IDEA launcher is unavailable: $idea_executable"
  [[ -f "$idea_vm_options" ]] || fail "IntelliJ IDEA VM options are unavailable: $idea_vm_options"
  python3 "$python_checks" product "$idea_app"
}

create_scratch() {
  scratch="$(mktemp -d "$temp_root/kast-foreground-worktree-smoke.XXXXXX")"
  scratch="$(cd -- "$scratch" && pwd -P)"
  : >"$scratch/.kast-foreground-worktree-smoke"
  python3 "$process_checks" init "$scratch" "$scratch/process-ownership.json"
}
register_pid() {
  python3 "$process_checks" register "$scratch/process-ownership.json" "$1"
}

process_identity() {
  ps -ww -p "$1" -o uid= -o command= 2>/dev/null | sed -e 's/^[[:space:]]*//'
}

fixture_owns_pid() {
  python3 "$process_checks" owns "$scratch/process-ownership.json" "$1"
}

register_fixture_processes() {
  python3 "$process_checks" capture "$scratch/process-ownership.json"
}

stop_fixture_processes() {
  [[ ! -f "$scratch/process-ownership.json" ]] || \
    python3 "$process_checks" stop "$scratch/process-ownership.json"
}

cleanup() {
  [[ -n "$scratch" && -d "$scratch" ]] || return 0
  stop_fixture_processes
  [[ -f "$scratch/.kast-foreground-worktree-smoke" ]] || fail "refusing to remove unmarked scratch path"
  case "$scratch" in
    "$temp_root"/kast-foreground-worktree-smoke.*) find "$scratch" -depth -delete ;;
    *) fail "refusing to remove unexpected scratch path: $scratch" ;;
  esac
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

paths_overlap() {
  python3 "$python_checks" overlaps "$1" "$2"
}

idea_digest() {
  python3 "$python_checks" digest "$1"
}

failure_diagnostics() {
  local diagnostic
  printf 'diagnostic: %s\n' "$1" >&2
  for diagnostic in "$scratch/foreground/stderr.log" "$scratch/foreground/stdout.log" \
    "$scratch/foreground/log/idea.log" "$scratch/status.stderr"; do
    [[ -s "$diagnostic" ]] || continue
    printf 'diagnostic file: %s\n' "$diagnostic" >&2
    tail -n 40 "$diagnostic" >&2
  done
}

wait_for_file() {
  local path="$1" timeout="$2" label="$3" attempt
  for ((attempt = 0; attempt < timeout * 2; attempt++)); do
    [[ -e "$path" ]] && return 0
    register_fixture_processes
    sleep 0.5
  done
  failure_diagnostics "timed out waiting for $label: $path"
  fail "timed out waiting for $label: $path"
}

wait_for_idea_quiet() {
  local root="$1" previous="" current="" stable=0 attempt
  for attempt in {1..30}; do
    current="$(idea_digest "$root")"
    if [[ "$current" == "$previous" ]]; then
      stable=$((stable + 1))
      [[ "$stable" -ge 3 ]] && return 0
    else
      stable=0
    fi
    previous="$current"
    sleep 1
  done
  fail "foreground IDEA metadata did not become quiet: $root"
}

clean_git() {
  env -u GIT_DIR -u GIT_WORK_TREE -u GIT_COMMON_DIR -u GIT_INDEX_FILE \
    -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES \
    -u GIT_CONFIG -u GIT_CONFIG_PARAMETERS -u GIT_CONFIG_COUNT \
    -u GIT_IMPLICIT_WORK_TREE -u GIT_GRAFT_FILE -u GIT_NO_REPLACE_OBJECTS \
    -u GIT_REPLACE_REF_BASE -u GIT_PREFIX -u GIT_SHALLOW_FILE \
    -u GIT_NAMESPACE -u GIT_CEILING_DIRECTORIES \
    -u GIT_DISCOVERY_ACROSS_FILESYSTEM git "$@"
}

self_test() {
  python3 "$process_checks_test"
  create_scratch
  write_gradle_fixture "$scratch/static-fixture"
  python3 "$python_checks" settings-fixture \
    "$scratch/static-fixture/settings.gradle.kts" "$scratch/markers"
  local descendant_file="$scratch/descendant.pid"
  local descendant_gate="$scratch/descendant.exec"
  KAST_SELF_TEST_PID_FILE="$descendant_file" KAST_SELF_TEST_EXEC_GATE="$descendant_gate" \
    python3 -c 'import os,subprocess,sys,time; child=subprocess.Popen([sys.executable,"-c","import os,time; open(os.environ[\"KAST_SELF_TEST_PID_FILE\"],\"w\").write(str(os.getpid())); gate=os.environ[\"KAST_SELF_TEST_EXEC_GATE\"];\nwhile not os.path.exists(gate): time.sleep(0.05)\nos.execv(\"/bin/sleep\",[\"kast-self-test-execed\",\"60\"])"]); time.sleep(60)' \
      "$scratch/KastForegroundSmokeSelfTest" &
  local child=$!
  register_pid "$child"
  wait_for_file "$descendant_file" 5 'marker-free fixture descendant'
  local descendant
  descendant="$(<"$descendant_file")"
  register_fixture_processes
  fixture_owns_pid "$descendant" || fail "cleanup missed a fixture descendant without a scratch argv marker"
  fixture_owns_pid "$$" && fail "cleanup admitted a pre-existing process"
  fixture_owns_pid "$child" || fail "cleanup rejected its exact fixture process"
  kill -TERM "$child"
  wait "$child" 2>/dev/null || true
  fixture_owns_pid "$descendant" || fail "cleanup lost a captured descendant after reparenting"
  : >"$descendant_gate"
  local attempt command=""
  for attempt in {1..20}; do
    command="$(ps -ww -p "$descendant" -o command= 2>/dev/null || true)"
    [[ "$command" == *kast-self-test-execed* ]] && break
    sleep 0.05
  done
  [[ "$command" == *kast-self-test-execed* ]] || fail "fixture descendant did not exec"
  fixture_owns_pid "$descendant" || fail "cleanup lost a captured descendant after exec"
  paths_overlap "$scratch/a" "$scratch/a/child" || fail "overlap check missed a child path"
  paths_overlap "$scratch/a" "$scratch/b" && fail "overlap check rejected sibling paths"
  local config="$scratch/foreground/config"
  mkdir -p "$config"
  python3 -c 'import time; time.sleep(60)' \
    "-Didea.config.path=$config" '-Djava.awt.headless=false' &
  local foreground=$!
  register_pid "$foreground"
  [[ "$(find_foreground_pid "$config")" == "$foreground" ]] \
    || fail "foreground PID lookup still requires the transient workspace argument"
  stop_fixture_processes
  fixture_owns_pid "$descendant" && fail "cleanup left its marker-free descendant running"
  fixture_owns_pid "$foreground" && fail "cleanup left its foreground fixture running"
  printf 'PASS: smoke safety contract\n'
}

select_bundle() {
  local bundles
  if [[ -n "${KAST_SMOKE_BUNDLE:-}" ]]; then
    [[ -f "$KAST_SMOKE_BUNDLE" ]] || fail "bundle is unavailable: $KAST_SMOKE_BUNDLE"
    printf '%s\n' "$KAST_SMOKE_BUNDLE"
    return
  fi
  shopt -s nullglob
  bundles=("$repo_root"/build/setup/kast-*.tar.gz)
  shopt -u nullglob
  [[ "${#bundles[@]}" -eq 1 ]] || fail "expected one development setup bundle; set KAST_SMOKE_BUNDLE explicitly"
  printf '%s\n' "${bundles[0]}"
}

fixture_env() {
  env HOME="$scratch/home" KAST_HOME="$scratch/kast-home" \
    KAST_CONFIG_HOME="$scratch/config" "$@"
}

write_gradle_fixture() {
  local main="$1" kotlin_version marker_literal
  kotlin_version="$(sed -n 's/^kotlin = "\([^"]*\)"/\1/p' "$repo_root/gradle/libs.versions.toml")"
  [[ -n "$kotlin_version" ]] || fail "cannot resolve the repository Kotlin version"
  marker_literal="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$scratch/markers")"
  mkdir -p "$main/gradle/wrapper" "$main/src/main/kotlin"
  cp "$repo_root/gradlew" "$main/gradlew"
  cp "$repo_root/gradle/wrapper/gradle-wrapper.jar" "$main/gradle/wrapper/gradle-wrapper.jar"
  cp "$repo_root/gradle/wrapper/gradle-wrapper.properties" "$main/gradle/wrapper/gradle-wrapper.properties"
  # Kotlin DSL, not Bash, expands phase and cache paths.
  # shellcheck disable=SC2016
  printf '%s\n' \
    'rootProject.name = "kast-foreground-overlap-smoke"' \
    "val markerDirectory = java.io.File($marker_literal).also { it.mkdirs() }" \
    'val cache = gradle.startParameter.projectCacheDir?.canonicalFile' \
    '    ?: java.io.File(settingsDir, ".gradle").canonicalFile' \
    'val kastOwnedCache = cache.path.endsWith("${java.io.File.separator}gradle-project-cache")' \
    'val phase = if (kastOwnedCache) "headless" else "foreground"' \
    'val evidence = "${settingsDir.canonicalPath}\n${cache.path}"' \
    'java.io.File(markerDirectory, "$phase-entered").writeText(evidence)' \
    'Thread.sleep(if (phase == "foreground") 120_000L else 45_000L)' \
    'java.io.File(markerDirectory, "$phase-done").writeText(evidence)' \
    >"$main/settings.gradle.kts"
  printf '%s\n' \
    'plugins {' \
    "    kotlin(\"jvm\") version \"$kotlin_version\"" \
    '}' \
    'repositories { mavenCentral() }' >"$main/build.gradle.kts"
  printf '%s\n' 'org.gradle.daemon=false' >"$main/gradle.properties"
  printf '%s\n' 'fun main() = println("fixture")' >"$main/src/main/kotlin/Main.kt"
}

find_foreground_pid() {
  python3 "$process_checks" find-foreground "$scratch/process-ownership.json" "$1"
}

wait_for_ready() {
  local ctl="$1" workspace="$2" status="$3" error="$4" attempt
  for attempt in {1..240}; do
    if fixture_env "$ctl" --output json status --workspace-root "$workspace" >"$status" 2>"$error" && \
      python3 "$python_checks" status-ready "$status"
    then
      return 0
    fi
    register_fixture_processes
    sleep 2
  done
  failure_diagnostics "headless indexer did not reach READY"
  fail "headless indexer did not reach READY"
}

run_real_smoke() {
  preflight
  create_scratch
  printf 'Building a fixture-only development setup bundle...\n'
  (cd -- "$repo_root" && ./gradlew packageDevelopmentSetupBundle --console=plain)
  local bundle ctl setup_cli main linked foreground_root foreground_pid headless_pid storage_root
  bundle="$(select_bundle)"
  setup_cli="$repo_root/cli-rs/target/debug/kastctl"
  mkdir -p "$scratch/home" "$scratch/kast-home" "$scratch/config" "$scratch/markers"
  fixture_env "$setup_cli" --output json setup --source "$bundle" >"$scratch/setup.json"
  ctl="$scratch/kast-home/current/libexec/kastctl"
  [[ -x "$ctl" ]] || fail "fixture Kast control CLI was not installed"
  fixture_env "$ctl" developer runtime start-background --help >/dev/null
  local escaped_app="${idea_app//\\/\\\\}"
  escaped_app="${escaped_app//\"/\\\"}"
  printf '[indexer]\nhostCommand = "%s"\n[gradle]\ntoolingApiTimeoutMillis = 600000\n' \
    "$escaped_app" >"$scratch/config/config.toml"

  main="$scratch/repository/main"
  linked="$scratch/repository/linked"
  mkdir -p "$main"
  clean_git -C "$main" init --quiet --initial-branch=main
  clean_git -C "$main" config user.name 'Kast Smoke'
  clean_git -C "$main" config user.email 'kast-smoke@example.invalid'
  write_gradle_fixture "$main"
  clean_git -C "$main" add .
  clean_git -C "$main" commit --quiet -m fixture
  clean_git -C "$main" worktree add --quiet -b linked "$linked"
  [[ -f "$linked/.git" ]] || fail "fixture is not a real linked worktree"
  [[ ! -e "$linked/.idea" ]] || fail "linked worktree unexpectedly started with .idea metadata"
  fixture_env "$ctl" --output json developer inspect paths --workspace-root "$main" >"$scratch/main-paths.json"
  fixture_env "$ctl" --output json developer inspect paths --workspace-root "$linked" >"$scratch/linked-paths.json"

  foreground_root="$scratch/foreground"
  mkdir -p "$foreground_root/config" "$foreground_root/system" "$foreground_root/log" "$foreground_root/plugins"
  cp "$idea_vm_options" "$foreground_root/idea.vmoptions"
  printf '%s\n' \
    '-Djava.awt.headless=false' \
    '-Didea.paths.selector=KastForegroundWorktreeSmoke' \
    "-Didea.config.path=$foreground_root/config" \
    "-Didea.system.path=$foreground_root/system" \
    "-Didea.log.path=$foreground_root/log" \
    "-Didea.plugins.path=$foreground_root/plugins" \
    '-Didea.trust.all.projects=true' \
    '-Didea.initially.ask.config=never' \
    '-Dide.show.tips.on.startup.default.value=false' \
    '-Dnosplash=true' >>"$foreground_root/idea.vmoptions"
  HOME="$scratch/home" IDEA_VM_OPTIONS="$foreground_root/idea.vmoptions" \
    "$idea_executable" "$linked" >"$foreground_root/stdout.log" 2>"$foreground_root/stderr.log" &
  register_pid "$!"
  wait_for_file "$scratch/markers/foreground-entered" 180 'foreground Gradle import entry'
  wait_for_file "$linked/.idea" 30 'foreground-owned .idea metadata'
  wait_for_idea_quiet "$linked/.idea"
  foreground_pid="$(find_foreground_pid "$foreground_root/config")" \
    || fail "cannot identify the isolated foreground IDEA process"
  register_pid "$foreground_pid"
  local foreground_command
  foreground_command="$(process_identity "$foreground_pid")"
  [[ "$foreground_command" == *'-Djava.awt.headless=false'* ]] || fail "foreground IDEA is not proven non-headless"
  local source_before
  source_before="$(idea_digest "$linked/.idea")"

  local start_epoch elapsed
  start_epoch="$(date +%s)"
  fixture_env "$ctl" --output json developer runtime start-background \
    --workspace-root "$linked" --accept-indexing >"$scratch/start.json"
  elapsed=$(( $(date +%s) - start_epoch ))
  [[ "$elapsed" -lt 20 ]] || fail "background admission exceeded the provider hook budget: ${elapsed}s"
  read -r headless_pid storage_root < <(
    python3 "$python_checks" started "$scratch/start.json" "$linked"
  )
  register_pid "$headless_pid"
  fixture_owns_pid "$headless_pid" || fail "background result PID is not the exact fixture indexer"
  [[ "$(process_identity "$headless_pid")" == *"--indexer-storage-root=$storage_root"* ]] \
    || fail "background PID does not own the reported storage root"

  set +e
  fixture_env "$ctl" --output json developer runtime start-background \
    --workspace-root "$linked" --accept-indexing >"$scratch/collision.json" 2>"$scratch/collision.stderr"
  local collision_status=$?
  set -e
  [[ "$collision_status" -ne 0 ]] || fail "a pre-descriptor duplicate start was not rejected"
  python3 "$python_checks" collision "$scratch/collision.json"
  wait_for_file "$scratch/markers/headless-entered" 60 'headless Gradle import entry'
  [[ ! -e "$scratch/markers/foreground-done" ]] || fail "foreground import finished before overlap was proven"
  [[ "$(idea_digest "$linked/.idea")" == "$source_before" ]] \
    || fail "Kast bootstrap modified foreground-owned source .idea metadata"

  python3 "$python_checks" layout "$scratch/start.json" "$scratch/main-paths.json" \
    "$scratch/linked-paths.json" "$linked" "$foreground_root"
  [[ "$(sed -n '1p' "$scratch/markers/headless-entered")" == "$linked" ]] \
    || fail "headless Gradle marker did not bind the exact linked worktree"
  [[ "$(sed -n '2p' "$scratch/markers/headless-entered")" == "$storage_root/gradle-project-cache" ]] \
    || fail "headless Gradle import did not use its Kast-owned project cache"
  local foreground_cache
  [[ "$(sed -n '1p' "$scratch/markers/foreground-entered")" == "$linked" ]] \
    || fail "foreground Gradle marker did not bind the exact linked worktree"
  foreground_cache="$(sed -n '2p' "$scratch/markers/foreground-entered")"
  [[ "$foreground_cache" != "$storage_root/gradle-project-cache" ]] \
    || fail "foreground IDEA and Kast shared one Gradle project cache"
  [[ "$foreground_cache" == "$linked/.gradle" ]] \
    || fail "foreground IDEA received an unexpected project-cache override: $foreground_cache"

  wait_for_ready "$ctl" "$linked" "$scratch/status.json" "$scratch/status.stderr"
  wait_for_file "$scratch/markers/foreground-done" 180 'foreground Gradle configuration completion'
  wait_for_file "$scratch/markers/headless-done" 30 'headless Gradle configuration completion'
  fixture_owns_pid "$foreground_pid" || fail "foreground IDEA exited during concurrent indexing"
  if grep -R -Eiq 'timeout waiting to lock|could not (acquire )?lock|cannot lock' \
    "$foreground_root/log" "$storage_root/idea-log"; then
    fail "a fixture Gradle or IDEA storage lock failed during overlap"
  fi
  fixture_env "$ctl" --output json developer runtime start-background \
    --workspace-root "$linked" --accept-indexing >"$scratch/reused.json"
  python3 "$python_checks" reused "$scratch/reused.json" "$headless_pid" "$storage_root"
  wait_for_idea_quiet "$linked/.idea"
  source_before="$(idea_digest "$linked/.idea")"
  sleep 2
  [[ "$(idea_digest "$linked/.idea")" == "$source_before" ]] \
    || fail "source .idea changed after exact runtime reuse"
  register_fixture_processes
  printf 'PASS: foreground IDEA and headless Kast indexed one linked worktree with isolated writable storage\n'
  printf 'Foreground PID: %s\nHeadless PID: %s\nStorage root: %s\n' \
    "$foreground_pid" "$headless_pid" "$storage_root"
}

case "$mode" in
  --self-test) self_test ;;
  --preflight-only)
    preflight
    printf 'PASS: real-host foreground smoke preflight (no process launched)\n'
    ;;
  --run-foreground) run_real_smoke ;;
  *) usage ;;
esac
