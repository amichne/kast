#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: benchmark-real-repositories.sh \
  --name <slug> \
  --repository <https://github.com/owner/repo.git> \
  --revision <40-hex commit> \
  --bundle <linux setup tarball> \
  --cache-root <directory>
EOF
}

name=
repository=
revision=
bundle=
cache_root=
wait_timeout_ms=1200000
while (($#)); do
  case "$1" in
    --name) name="${2:-}"; shift 2 ;;
    --repository) repository="${2:-}"; shift 2 ;;
    --revision) revision="${2:-}"; shift 2 ;;
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
workspace="$scratch/workspace"
bundle_dir="$scratch/bundle"
benchmark_user_dir="$scratch/user"
kast_home_dir="$scratch/kast-home"
kast_cache_dir="$scratch/kast-cache"
gradle_user_dir="$scratch/gradle"
mkdir -p "$bundle_dir" "$benchmark_user_dir" "$kast_home_dir" "$kast_cache_dir" "$gradle_user_dir"

cleanup() {
  if [[ -n "${kast_bin:-}" && -x "${kast_bin:-}" && -d "$workspace" ]]; then
    kast developer runtime stop --backend headless --workspace-root "$workspace" >/dev/null 2>&1 || true
  fi
  git -C "$repository_cache" worktree remove --force "$workspace" >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT

git -C "$repository_cache" worktree add --detach "$workspace" "$revision"
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

kast config set indexing.phase2Parallelism 2 --workspace-root "$workspace" >/dev/null
kast config set gradle.toolingApiTimeoutMillis "$wait_timeout_ms" --workspace-root "$workspace" >/dev/null
kast config list --workspace-root "$workspace" >"$scratch/effective-config.toon"
kast --output json developer runtime up \
  --backend headless \
  --workspace-root "$workspace" \
  --accept-indexing=true \
  --wait-timeout-ms "$wait_timeout_ms" >"$scratch/runtime.json"
kast --output json agent workspace-files \
  --backend headless \
  --workspace-root "$workspace" \
  --count >"$scratch/workspace-files.json"
kast --output json agent graph \
  --backend headless \
  --workspace-root "$workspace" \
  --operation summary >"$scratch/graph.json"

python3 - "$scratch/workspace-files.json" "$scratch/graph.json" <<'PY'
import json
import sys
from pathlib import Path

workspace_files = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
cardinality = workspace_files["result"]["cardinality"]
if cardinality.get("type") != "EXACT" or cardinality.get("totalCount", 0) <= 0:
    raise SystemExit(f"workspace indexing was not complete: {cardinality}")

graph = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
if not graph.get("ok") or graph.get("result", {}).get("nodeCount", 0) <= 0:
    raise SystemExit("native graph summary was unavailable")
PY

printf 'benchmark %s passed at %s\n' "$name" "$revision"
