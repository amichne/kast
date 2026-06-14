#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-lsp-gates.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

sdk_surfaces=(
  "${repo_root}/.github/lsp.json"
  "${repo_root}/.github/hooks"
  "${repo_root}/kast-copilot-plugin"
)
if rg -n "@github/copilot-sdk|joinSession|extension\\.mjs" "${sdk_surfaces[@]}" >"${tmp_dir}/sdk-hits.txt"; then
  printf 'normal LSP, hook, and plugin surfaces must not depend on the deprecated SDK path:\n' >&2
  sed -n '1,120p' "${tmp_dir}/sdk-hits.txt" >&2
  exit 1
fi

python3 - "$repo_root" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])

def fail(message: str) -> None:
    raise SystemExit(message)

def require_text(path: str, needles: dict[str, str]) -> str:
    text = (root / path).read_text()
    for label, needle in needles.items():
        if needle not in text:
            fail(f"{path} missing {label}: {needle}")
    return text

repo_lsp = json.loads((root / ".github/lsp.json").read_text())
plugin_lsp = json.loads((root / "kast-copilot-plugin/lsp.json").read_text())
repo_server = repo_lsp["lspServers"]["kast-kotlin"]
plugin_server = plugin_lsp["lspServers"]["kast-kotlin"]
if repo_server != plugin_server:
    fail("plugin LSP configuration drifted from repository LSP configuration")
if repo_server["command"] != "kast" or repo_server["args"] != ["lsp", "--stdio"]:
    fail("kast-kotlin must launch kast lsp --stdio")
if repo_server["initializationOptions"]["failOnStaleIndex"] is not True:
    fail("kast-kotlin must fail closed on stale indexes")

hooks = json.loads((root / ".github/hooks/hooks.json").read_text())
plugin_hooks = json.loads((root / "kast-copilot-plugin/hooks/hooks.json").read_text())
if hooks != plugin_hooks:
    fail("plugin hook configuration drifted from repository hook configuration")
for event in ("sessionStart", "preToolUse", "postToolUse", "sessionEnd"):
    commands = hooks["hooks"].get(event)
    if not commands or commands[0].get("type") != "command":
        fail(f"missing command hook for {event}")

lsp = require_text("cli-rs/src/lsp.rs", {
    "bounded result cap": "const MAX_LSP_RESULTS",
    "bounded result application": ".take(MAX_LSP_RESULTS)",
    "generated custom route include": "lsp_custom_routes.rs",
    "custom route lookup": "custom_lsp_route",
    "custom route table": "KAST_CUSTOM_LSP_ROUTES",
    "read methods": '"textDocument/references"',
    "prepare rename method": '"textDocument/prepareRename"',
    "rename method": '"textDocument/rename"',
    "rename capability gate": "mutationCapabilities",
    "partial reference rejection": "LSP_RENAME_PARTIAL_REFERENCE_SET",
    "generated rename rejection": "rename edit would modify generated or build output",
    "initialization options parser": "initializationOptions.failOnStaleIndex",
    "stale index failure": "LSP_STALE_INDEX",
    "runtime status check": '"runtime/status"',
    "backend error data propagation": "backendCode",
    "ambiguous backend test": "AMBIGUOUS_ANCHOR",
    "runtime timeout backend test": "RUNTIME_TIMEOUT",
})
if "contents" in lsp and "document_symbols_map_nested_outline_without_file_contents" not in lsp:
    fail("document symbol tests must prove outlines do not expose file contents")
if '"kast/symbolResolve" =>' in lsp or '"kast/databaseMetrics" =>' in lsp:
    fail("custom kast/* dispatch must be generated from the RPC catalog, not hand-written match arms")

build_rs = require_text("cli-rs/build.rs", {
    "LSP route generator": "lsp_custom_routes.rs",
    "RPC catalog input": "resources/kast-skill/references/commands.json",
})
if "symbol/resolve" in build_rs or "database/metrics" in build_rs:
    fail("LSP route generation must read method names from the catalog instead of hard-coding routes")

install_rs = require_text("cli-rs/src/install.rs", {
    "Copilot plugin agents": "../kast-copilot-plugin/agents",
    "Copilot plugin skills": "../kast-copilot-plugin/skills",
    "explicit package file manifest": "COPILOT_PLUGIN_FILES",
    "repo skill target": ".agents/skills",
    "bounded package refresh": "install_copilot_package_entries",
})
if "resources/copilot-extension" in install_rs or "extensions/kast" in install_rs:
    fail("kast install copilot must install the LSP package, not the deprecated SDK extension")

require_text("cli-rs/src/rpc.rs", {
    "backend error code preservation": '"backendCode"',
    "backend code test": "preserves_backend_error_code",
})
require_text(".github/hooks/kast-hook-policy.py", {
    "policy forwarder": "kast-copilot-plugin",
})
instructions = require_text(".github/copilot-instructions.md", {
    "LSP custom methods": "capabilities.experimental.kastMethods",
    "primary Copilot package": "kast-copilot-plugin/",
    "SDK fallback wording": "native-tool extension is a fallback",
})
if "extension.mjs` is the primary" in instructions:
    fail("Copilot instructions must keep the LSP plugin primary over the SDK extension")
require_text("kast-copilot-plugin/instructions/kast-kotlin.md", {
    "LSP custom methods": "capabilities.experimental.kastMethods",
    "custom request routing": "`kast/*` custom requests",
})
require_text("kast-copilot-plugin/hooks/kast-hook-policy.py", {
    "broad search denial": "Broad text search over Kotlin sources is blocked",
    "large read denial": "Full-file reads above the configured size threshold are blocked",
    "post edit validation": "raw/diagnostics",
    "completion validation gate": "Completion blocked because Kotlin edits lack clean Kast diagnostics",
})

print("LSP pivot static gates passed")
PY

"${repo_root}/.github/scripts/test-kast-hooks.sh" >/dev/null
"${repo_root}/.github/scripts/test-kast-copilot-plugin.sh" >/dev/null

if [[ -z "${KAST_LSP_TEST_COMMAND:-}" ]]; then
  if [[ -x "${repo_root}/cli-rs/target/debug/kast" ]]; then
    export KAST_LSP_TEST_COMMAND="${repo_root}/cli-rs/target/debug/kast"
  elif [[ -x "${repo_root}/cli-rs/target/release/kast" ]]; then
    export KAST_LSP_TEST_COMMAND="${repo_root}/cli-rs/target/release/kast"
  else
    die "KAST_LSP_TEST_COMMAND is required when no local kast binary has been built"
  fi
fi
export KAST_LSP_REQUEST_TIMEOUT_MS="${KAST_LSP_REQUEST_TIMEOUT_MS:-1000}"
node "${repo_root}/.github/scripts/test-lsp-config.mjs" >"${tmp_dir}/lsp-smoke.json"

python3 - "${tmp_dir}/lsp-smoke.json" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
allowed_fail_closed = {
    "DAEMON_START_ERROR",
    "HEADLESS_BACKEND_NOT_INSTALLED",
    "IDEA_NOT_RUNNING",
    "NO_BACKEND_AVAILABLE",
    "RUNTIME_TIMEOUT",
}
code = payload.get("initializeErrorCode")
if code is not None and code not in allowed_fail_closed:
    raise SystemExit(f"unexpected LSP initialize failure code: {code}")
print(json.dumps({
    "ok": True,
    "initializeErrorCode": code,
    "command": payload.get("command"),
}, sort_keys=True))
PY

printf 'LSP pivot gates passed\n'
