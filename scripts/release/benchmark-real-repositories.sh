#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: benchmark-real-repositories.sh \
  --name <slug> \
  --repository <https://github.com/owner/repo.git> \
  --revision <40-hex commit> \
  --graph-file <relative Kotlin path> \
  --relationships-enabled <true|false> \
  --bundle <linux setup tarball> \
  --cache-root <directory>
EOF
}

valid_graph_file() {
  local path="$1" component
  local -a components
  [[ -n "$path" && "$path" != /* && "$path" == *.kt ]] || return 1
  IFS=/ read -r -a components <<<"$path"
  for component in "${components[@]}"; do
    [[ "$component" =~ ^[A-Za-z0-9._-]+$ && "$component" != . && "$component" != .. ]] || return 1
  done
}

gradle_workspace_for() {
  local graph_path="$1" repository_root="$2" workspace
  workspace="$(dirname "$graph_path")"
  while [[ "$workspace" != "$repository_root" \
      && ! -f "$workspace/settings.gradle" \
      && ! -f "$workspace/settings.gradle.kts" ]]; do
    workspace="$(dirname "$workspace")"
  done
  [[ -f "$workspace/settings.gradle" || -f "$workspace/settings.gradle.kts" ]] || return 1
  printf '%s\n' "$workspace"
}

configure_gradle_java_paths() {
  local gradle_user_dir="$1" gradle_java_home="${2:-}" java_home="${3:-}"
  [[ -n "$gradle_java_home" ]] || return 0
  [[ -d "$gradle_java_home" ]] || { printf 'error: Gradle Java home not found: %s\n' "$gradle_java_home" >&2; return 1; }
  [[ -d "$java_home" ]] || { printf 'error: Java home not found: %s\n' "$java_home" >&2; return 1; }
  printf 'org.gradle.java.installations.paths=%s,%s\n' "$gradle_java_home" "$java_home" \
    >"$gradle_user_dir/gradle.properties"
}

[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0

name=
repository=
revision=
graph_file=
relationships_enabled=
bundle=
cache_root=
wait_timeout_ms=2700000
while (($#)); do
  case "$1" in
    --name) name="${2:-}"; shift 2 ;;
    --repository) repository="${2:-}"; shift 2 ;;
    --revision) revision="${2:-}"; shift 2 ;;
    --graph-file) graph_file="${2:-}"; shift 2 ;;
    --relationships-enabled) relationships_enabled="${2:-}"; shift 2 ;;
    --bundle) bundle="${2:-}"; shift 2 ;;
    --cache-root) cache_root="${2:-}"; shift 2 ;;
    --wait-timeout-ms) wait_timeout_ms="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'error: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$name" =~ ^[a-z0-9-]+$ ]] || { printf 'error: invalid benchmark name\n' >&2; exit 2; }
[[ "$repository" =~ ^https://github\.com/[^/]+/[^/]+\.git$ ]] \
  || { printf 'error: benchmark repository must be a GitHub HTTPS clone URL\n' >&2; exit 2; }
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { printf 'error: revision must be a full commit SHA\n' >&2; exit 2; }
valid_graph_file "$graph_file" \
  || { printf 'error: graph file must be a relative Kotlin path\n' >&2; exit 2; }
[[ "$relationships_enabled" == true || "$relationships_enabled" == false ]] \
  || { printf 'error: relationships enabled must be true or false\n' >&2; exit 2; }
[[ -f "$bundle" ]] || { printf 'error: bundle not found: %s\n' "$bundle" >&2; exit 2; }
[[ -n "$cache_root" ]] || { printf 'error: --cache-root is required\n' >&2; exit 2; }
[[ "$wait_timeout_ms" =~ ^[1-9][0-9]*$ ]] || { printf 'error: wait timeout must be positive\n' >&2; exit 2; }

cache_root="$(mkdir -p "$cache_root" && cd "$cache_root" && pwd)"
repository_cache="$cache_root/$name"
if [[ ! -d "$repository_cache/.git" ]]; then
  git clone --filter=blob:none --no-checkout "$repository" "$repository_cache"
fi
git -C "$repository_cache" remote set-url origin "$repository"
git -C "$repository_cache" fetch --force --depth=1 origin "$revision"
git -C "$repository_cache" cat-file -e "${revision}^{commit}"

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-real-repository.XXXXXX")"
repository_worktree="$scratch/repository"
workspace=
bundle_dir="$scratch/bundle"
benchmark_user_dir="$scratch/user"
kast_home_dir="$scratch/kast-home"
kast_cache_dir="$scratch/kast-cache"
gradle_user_dir="$scratch/gradle"
mkdir -p "$bundle_dir" "$benchmark_user_dir" "$kast_home_dir" "$kast_cache_dir" "$gradle_user_dir"
configure_gradle_java_paths "$gradle_user_dir" "${GRADLE_JAVA_HOME:-}" "${JAVA_HOME:-}"

cleanup() {
  if [[ -n "${kast_bin:-}" && -x "${kast_bin:-}" && -n "$workspace" && -d "$workspace" ]]; then
    kast developer runtime stop --backend headless --workspace-root "$workspace" >/dev/null 2>&1 || true
  fi
  git -C "$repository_cache" worktree remove --force "$repository_worktree" >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT

git -C "$repository_cache" worktree add --detach "$repository_worktree" "$revision"
graph_path="$repository_worktree/$graph_file"
[[ -f "$graph_path" ]] \
  || { printf 'error: graph file not found: %s\n' "$graph_file" >&2; exit 1; }
workspace="$(gradle_workspace_for "$graph_path" "$repository_worktree")" \
  || { printf 'error: graph file is not inside a Gradle build: %s\n' "$graph_file" >&2; exit 1; }
scoped_graph_file="${graph_path#"$workspace"/}"
tar -xzf "$bundle" -C "$bundle_dir"
bundle_bin="$(find "$bundle_dir" -maxdepth 3 -type f -path '*/bin/kast' -print -quit)"
[[ -n "$bundle_bin" ]] || { printf 'error: bundle does not contain bin/kast\n' >&2; exit 1; }
bundle_root="$(cd "$(dirname "$bundle_bin")/.." && pwd)"
chmod 755 "$bundle_bin"

env \
  HOME="$benchmark_user_dir" \
  KAST_HOME="$kast_home_dir" \
  KAST_CACHE_HOME="$kast_cache_dir" \
  GRADLE_USER_HOME="$gradle_user_dir" \
  "$bundle_bin" --output json setup --source "$bundle_root" >/dev/null
kast_bin="$kast_home_dir/current/bin/kast"

kast() {
  env \
    HOME="$benchmark_user_dir" \
    KAST_HOME="$kast_home_dir" \
    KAST_CACHE_HOME="$kast_cache_dir" \
    GRADLE_USER_HOME="$gradle_user_dir" \
    KAST_WORKSPACE_ID="release-benchmark-$name" \
    "$kast_bin" "$@"
}

kast config set indexing.relationships.enabled "$relationships_enabled" --workspace-root "$workspace" >/dev/null
if [[ "$relationships_enabled" == true ]]; then
  kast config set indexing.relationships.parallelism 2 --workspace-root "$workspace" >/dev/null
fi
kast config set gradle.toolingApiTimeoutMillis "$wait_timeout_ms" --workspace-root "$workspace" >/dev/null
kast config list --workspace-root "$workspace" >"$scratch/effective-config.toon"
if ! kast --output json developer runtime up \
    --backend headless \
    --workspace-root "$workspace" \
    --wait-timeout-ms "$wait_timeout_ms" >"$scratch/runtime.json"; then
  cat "$scratch/runtime.json" >&2
  exit 1
fi
kast --output json agent workspace-files \
  --backend headless \
  --workspace-root "$workspace" \
  --count >"$scratch/workspace-files.json"
kast --output json agent graph \
  --backend headless \
  --workspace-root "$workspace" \
  --operation refresh \
  --file-path "$scoped_graph_file" \
  --exclusive >"$scratch/graph-refresh.json"
kast --output json agent graph \
  --backend headless \
  --workspace-root "$workspace" \
  --operation summary >"$scratch/graph.json"

python3 - "$scratch/workspace-files.json" "$scratch/graph-refresh.json" "$scratch/graph.json" <<'PY'
import json
import sys
from pathlib import Path

workspace_files = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
cardinality = workspace_files["result"]["cardinality"]
if cardinality.get("type") != "EXACT" or cardinality.get("totalCount", 0) <= 0:
    raise SystemExit(f"workspace indexing was not complete: {cardinality}")

refresh = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
coverage = refresh.get("result", {}).get("coverage", {}).get("files", [])
if (
    not refresh.get("ok")
    or refresh.get("result", {}).get("symbolCount", 0) <= 0
    or len(coverage) != 1
    or coverage[0].get("status") != "REFRESHED"
):
    raise SystemExit(f"Semantic graph refresh was incomplete: {refresh}")

graph = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8"))
if not graph.get("ok") or graph.get("result", {}).get("nodeCount", 0) <= 0:
    raise SystemExit("native graph summary was unavailable")
PY

printf 'benchmark %s passed at %s\n' "$name" "$revision"
