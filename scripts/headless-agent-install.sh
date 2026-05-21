#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '%s\n' "$*" >&2
}

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

absolute_path() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

print(Path(sys.argv[1]).expanduser().resolve())
PY
}

compute_sha256() {
  local input_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$input_path" | awk '{ print $1 }'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$input_path" | awk '{ print $1 }'
    return
  fi
  die "Neither sha256sum nor shasum is available for checksum computation"
}

normalize_sha256() {
  local digest="$1"
  digest="${digest#sha256:}"
  printf '%s\n' "$digest"
}

verify_sha256() {
  local file_path="$1"
  local expected="$2"
  local label="$3"

  [[ -n "$expected" ]] || return 0
  expected="$(normalize_sha256 "$expected")"
  local actual; actual="$(compute_sha256 "$file_path")"
  [[ "$actual" == "$expected" ]] || die "${label} checksum mismatch"
  log "Verified ${label} SHA-256"
}

download_artifact() {
  local url="$1"
  local output_path="$2"
  local label="$3"

  log "Downloading ${label}"
  curl \
    --fail \
    --location \
    --retry 3 \
    --retry-delay 2 \
    --silent \
    --show-error \
    --output "$output_path" \
    "$url"
}

write_env_file() {
  local env_file="$1"
  local install_root="$2"
  {
    printf '# Kast environment for headless agent sessions.\n'
    printf 'export KAST_CONFIG_HOME="%s/config"\n' "$install_root"
    printf 'export PATH="%s/bin:$PATH"\n' "$install_root"
  } > "$env_file"
}

verify_install() {
  local install_root="$1"
  local workspace_root="$2"
  local skip_copilot_extension="$3"

  local kast_bin="${install_root}/bin/kast"
  local config_file="${install_root}/config/config.toml"
  local manifest_file="${install_root}/.manifest.json"
  local runtime_libs_dir="${install_root}/backends/current/runtime-libs"
  local skill_dir="${install_root}/lib/skills/kast"
  local extension_marker="${workspace_root}/.github/.kast-copilot-version"
  local copilot_root="${workspace_root}/.github"

  [[ -x "$kast_bin" ]] || die "Installed kast launcher is missing: $kast_bin"
  "$kast_bin" --help >/dev/null

  [[ -f "$config_file" ]] || die "Kast config was not written: $config_file"
  grep -Fq "installRoot = \"${install_root}\"" "$config_file" || die "config.toml has the wrong installRoot"
  grep -Fq "binaryPath = \"${kast_bin}\"" "$config_file" || die "config.toml has the wrong cli.binaryPath"
  [[ -d "$runtime_libs_dir" ]] || die "Standalone runtime libs were not installed: $runtime_libs_dir"

  [[ -f "${skill_dir}/SKILL.md" ]] || die "Packaged skill was not installed: ${skill_dir}/SKILL.md"
  if [[ "$skip_copilot_extension" != "true" ]]; then
    [[ -f "$extension_marker" ]] || die "Copilot extension was not installed: $extension_marker"
    [[ -f "${copilot_root}/agents/kast-orchestrator.md" ]] || die "Copilot agent was not installed: ${copilot_root}/agents/kast-orchestrator.md"
    [[ -f "${copilot_root}/hooks/hooks.json" ]] || die "Copilot hooks were not installed: ${copilot_root}/hooks/hooks.json"
    [[ -f "${copilot_root}/extensions/kast/extension.mjs" ]] || die "Kast Copilot extension was not installed: ${copilot_root}/extensions/kast/extension.mjs"
    [[ -x "${copilot_root}/extensions/kast/scripts/resolve-kast.sh" ]] || die "Kast resolver was not installed executable: ${copilot_root}/extensions/kast/scripts/resolve-kast.sh"
    [[ -f "${copilot_root}/extensions/kotlin-gradle-loop/extension.mjs" ]] || die "Kotlin Gradle loop extension was not installed: ${copilot_root}/extensions/kotlin-gradle-loop/extension.mjs"
  fi
  [[ -f "$manifest_file" ]] || die "Install manifest was not written: $manifest_file"

  python3 - "$manifest_file" "$workspace_root" "$skip_copilot_extension" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
workspace_root = str(Path(sys.argv[2]).resolve())
skip_copilot_extension = sys.argv[3] == "true"
components = set(manifest.get("components", []))
missing = {"cli", "backend", "skill"} - components
if missing:
    raise SystemExit(f"manifest missing components: {sorted(missing)}")
repos = {entry.get("path") for entry in manifest.get("repos", [])}
if not skip_copilot_extension and workspace_root not in repos:
    raise SystemExit(f"manifest missing managed repo: {workspace_root}")
PY
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/headless-agent-install.sh

Required environment:
  KAST_AGENT_CLI_URL       Direct URL for the internal Kast CLI zip
  KAST_AGENT_BACKEND_URL   Direct URL for the internal standalone backend zip

Optional environment:
  KAST_AGENT_CLI_SHA256        Expected SHA-256 for KAST_AGENT_CLI_URL
  KAST_AGENT_BACKEND_SHA256    Expected SHA-256 for KAST_AGENT_BACKEND_URL
  KAST_AGENT_INSTALL_ROOT      Contained install root (default: $HOME/.kast-agent)
  KAST_AGENT_WORKSPACE         Git workspace for Copilot extension install (default: $PWD)
  KAST_AGENT_VERSION           Version label written to Kast install metadata (default: agent)
  KAST_SKIP_COPILOT_EXTENSION  Set true to skip Copilot extension install
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
[[ $# -eq 0 ]] || { usage; die "Unexpected arguments: $*"; }

need_tool curl
need_tool git
need_tool python3

repo_root="$(resolve_repo_root)"
[[ -f "${repo_root}/kast.sh" ]] || die "Could not find kast.sh at ${repo_root}/kast.sh"

cli_url="${KAST_AGENT_CLI_URL:-}"
backend_url="${KAST_AGENT_BACKEND_URL:-}"
[[ -n "$cli_url" ]] || die "KAST_AGENT_CLI_URL is required"
[[ -n "$backend_url" ]] || die "KAST_AGENT_BACKEND_URL is required"

install_root="$(absolute_path "${KAST_AGENT_INSTALL_ROOT:-${HOME}/.kast-agent}")"
workspace_input="$(absolute_path "${KAST_AGENT_WORKSPACE:-$PWD}")"
workspace_root="$(git -C "$workspace_input" rev-parse --show-toplevel 2>/dev/null)" \
  || die "KAST_AGENT_WORKSPACE must point inside a Git workspace: $workspace_input"
workspace_root="$(absolute_path "$workspace_root")"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-agent-install.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

mkdir -p "$install_root"

cli_archive="${tmp_dir}/kast-cli.zip"
backend_archive="${tmp_dir}/kast-standalone.zip"

download_artifact "$cli_url" "$cli_archive" "Kast CLI"
verify_sha256 "$cli_archive" "${KAST_AGENT_CLI_SHA256:-}" "Kast CLI"

download_artifact "$backend_url" "$backend_archive" "standalone backend"
verify_sha256 "$backend_archive" "${KAST_AGENT_BACKEND_SHA256:-}" "standalone backend"

log "Installing Kast into ${install_root}"
install_args=(install --components=cli,backend --yes)
skip_copilot_extension="${KAST_SKIP_COPILOT_EXTENSION:-false}"
if [[ "$skip_copilot_extension" == "true" ]]; then
  install_args+=(--skip-copilot-extension)
fi
(
  cd "$workspace_root"
  KAST_MANAGED_ROOT="$install_root" \
  KAST_CONFIG_HOME="${install_root}/config" \
  KAST_PATH_RC_FILE="${install_root}/shellrc" \
  KAST_INSTALL_COMPLETIONS=true \
  KAST_ARCHIVE_PATH="$cli_archive" \
  KAST_EXPECTED_SHA256="${KAST_AGENT_CLI_SHA256:-}" \
  KAST_BACKEND_ARCHIVE_PATH="$backend_archive" \
  KAST_BACKEND_EXPECTED_SHA256="${KAST_AGENT_BACKEND_SHA256:-}" \
  KAST_SKILL_SCOPE=global \
  KAST_INSTALL_SOURCE="${KAST_INSTALL_SOURCE:-release}" \
  KAST_VERSION="${KAST_AGENT_VERSION:-${KAST_VERSION:-agent}}" \
  /bin/bash "${repo_root}/kast.sh" "${install_args[@]}"
)

write_env_file "${install_root}/kast-env.sh" "$install_root"
verify_install "$install_root" "$workspace_root" "$skip_copilot_extension"

log "Kast headless agent install complete"
log "Source environment: ${install_root}/kast-env.sh"
