#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/smoke-native-cli.sh <kast-binary>

Smoke a native Kast CLI binary deeply enough to prove embedded agent resources
are present, not just that --help starts.
USAGE
}

[[ "${1:-}" != "--help" && "${1:-}" != "-h" ]] || { usage; exit 0; }
[[ $# -eq 1 ]] || { usage; die "Expected exactly one kast binary path"; }

kast_bin="$1"
[[ -x "$kast_bin" ]] || die "Kast binary is missing or not executable: $kast_bin"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-native-smoke.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

skill_root="${scratch_dir}/skills"
config_home="${scratch_dir}/config"
workspace_root="${scratch_dir}/workspace"
github_root="${workspace_root}/.github"

mkdir -p "$skill_root" "$config_home" "$workspace_root"
git -C "$workspace_root" init -q

cat > "${config_home}/config.toml" <<EOF_CONFIG
[backends.standalone]
runtimeLibsDir = "${scratch_dir}/runtime-libs"
EOF_CONFIG

"$kast_bin" --help >/dev/null

KAST_CONFIG_HOME="$config_home" "$kast_bin" status --workspace-root="$workspace_root" >/dev/null

"$kast_bin" install skill --target-dir="$skill_root" --name=kast --yes=true >/dev/null
[[ -f "${skill_root}/kast/SKILL.md" ]] || die "Native CLI did not install packaged skill"
[[ -f "${skill_root}/kast/references/commands.json" ]] || die "Native CLI skill install missed commands.json"
[[ -x "${skill_root}/kast/scripts/resolve-kast.sh" ]] || die "Native CLI skill install missed executable resolver"

"$kast_bin" install copilot-extension --target-dir="$github_root" --yes=true >/dev/null
[[ -f "${github_root}/agents/kast-orchestrator.md" ]] || die "Native CLI did not install Copilot agent"
[[ -f "${github_root}/hooks/hooks.json" ]] || die "Native CLI did not install Copilot hooks"
[[ -f "${github_root}/extensions/kast/extension.mjs" ]] || die "Native CLI did not install kast native extension"
[[ -x "${github_root}/extensions/kast/scripts/resolve-kast.sh" ]] || die "Native CLI did not install executable kast resolver"
[[ -f "${github_root}/extensions/kotlin-gradle-loop/extension.mjs" ]] || die "Native CLI did not install Kotlin Gradle loop extension"

printf '%s\n' "Native CLI embedded-resource smoke test passed"
