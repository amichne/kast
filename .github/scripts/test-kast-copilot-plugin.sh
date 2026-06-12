#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
plugin_root="${repo_root}/kast-copilot-plugin"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-plugin-test.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT

python3 - "$plugin_root" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "plugin.json").read_text())
assert manifest["schemaVersion"] == 1
assert manifest["name"] == "kast-copilot-lsp"
entrypoints = manifest["entrypoints"]
for key in ["lsp", "hooks"]:
    assert (root / entrypoints[key]).is_file(), key
for section in ["instructions", "agents", "skills"]:
    for relative in entrypoints[section]:
        assert (root / relative).is_file(), relative

lsp = json.loads((root / "lsp.json").read_text())
server = lsp["lspServers"]["kast-kotlin"]
assert server["args"] == ["lsp", "--stdio"]
assert server["initializationTimeoutMs"] >= 120000
assert server["initializationOptions"]["failOnStaleIndex"] is True

hooks = json.loads((root / "hooks/hooks.json").read_text())
assert hooks["version"] == 1
for event in ["sessionStart", "preToolUse", "postToolUse", "sessionEnd"]:
    command = hooks["hooks"][event][0]["command"]
    assert command.startswith(".github/hooks/"), command
PY

"${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"

test -f "$tmp_dir/.github/lsp.json"
test -f "$tmp_dir/.github/hooks/hooks.json"
test -x "$tmp_dir/.github/hooks/kast-pre-tool-use.sh"
test -f "$tmp_dir/.github/agents/kast-explorer.agent.md"
test -f "$tmp_dir/.agents/skills/kast-safe-rename/SKILL.md"

if rg -n "@github/copilot-sdk|joinSession|extension.mjs" "$plugin_root" >"${tmp_dir}/sdk-hits.txt"; then
  printf 'plugin package must not reference deprecated SDK extension path:\n' >&2
  sed -n '1,120p' "${tmp_dir}/sdk-hits.txt" >&2
  exit 1
fi

KAST_HOOK_REPO_ROOT="$tmp_dir" \
KAST_HOOK_STATE_DIR="$tmp_dir/.agent-turn/kast-hooks" \
KAST_HOOK_RUN_DIAGNOSTICS=0 \
  "$tmp_dir/.github/hooks/kast-session-start.sh" <<<'{}' >/dev/null

set +e
hook_output="$(
  KAST_HOOK_REPO_ROOT="$tmp_dir" \
  KAST_HOOK_STATE_DIR="$tmp_dir/.agent-turn/kast-hooks" \
  "$tmp_dir/.github/hooks/kast-pre-tool-use.sh" \
  <<<'{"toolName":"shell","command":"rg AnalysisBackend src/main/kotlin"}'
)"
hook_status=$?
set -e
if [[ "$hook_status" -eq 0 ]]; then
  printf 'installed preToolUse hook should block broad Kotlin search: %s\n' "$hook_output" >&2
  exit 1
fi

printf 'Kast Copilot plugin tests passed\n'
