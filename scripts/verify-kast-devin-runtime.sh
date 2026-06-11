#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/verify-kast-devin-runtime.sh [--prefix <bundle-root>]

Verify an unpacked Kast Devin headless runtime bundle, then prove ordinary
`kast up` and `kast rpc` commands use its configured headless backend.
USAGE
}

resolve_default_prefix() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
  cd -- "${script_dir}/.." >/dev/null 2>&1 && pwd
}

prefix=""
tmp_dir=""
workspace_root=""

cleanup() {
  if [[ -n "$tmp_dir" ]]; then
    if [[ -n "$workspace_root" && -x "${prefix:-}/bin/kast" ]]; then
      KAST_CONFIG_HOME="$prefix" "${prefix}/bin/kast" stop --workspace-root "$workspace_root" >/dev/null 2>&1 || true
    fi
    rm -rf -- "$tmp_dir"
  fi
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix)
      [[ $# -ge 2 ]] || die "Missing value for --prefix"
      prefix="$2"; shift 2 ;;
    --prefix=*)
      prefix="${1#--prefix=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

if [[ -z "$prefix" ]]; then
  prefix="$(resolve_default_prefix)"
fi
prefix="$(cd -- "$prefix" >/dev/null 2>&1 && pwd)" || die "Bundle prefix not found: $prefix"

manifest="${prefix}/manifest.json"
config_file="${prefix}/config.toml"
[[ -f "$manifest" ]] || die "Missing manifest.json in $prefix"
[[ -f "$config_file" ]] || die "Missing config.toml in $prefix; run scripts/setup-kast-devin-runtime.sh first"

backend_install_name="$(python3 - "$manifest" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if payload.get("kind") != "KAST_DEVIN_HEADLESS_RUNTIME":
    raise SystemExit(f"unexpected bundle kind: {payload.get('kind')}")
if payload.get("platform") != "devin-headless-linux-x64":
    raise SystemExit(f"unexpected platform: {payload.get('platform')}")
backend_install_name = payload.get("backendInstallName")
if not isinstance(backend_install_name, str) or not backend_install_name:
    raise SystemExit("manifest is missing backendInstallName")
print(backend_install_name)
PY
)"

cli_path="${prefix}/bin/kast"
backend_root="${prefix}/lib/backends/${backend_install_name}"
runtime_libs="${backend_root}/runtime-libs"
idea_home="${backend_root}/idea-home"

[[ -x "$cli_path" ]] || die "Missing executable CLI: $cli_path"
[[ -x "${backend_root}/kast-headless" ]] || die "Missing executable headless launcher: ${backend_root}/kast-headless"
[[ -f "${runtime_libs}/classpath.txt" ]] || die "Missing runtime classpath: ${runtime_libs}/classpath.txt"
[[ -f "${idea_home}/lib/nio-fs.jar" ]] || die "Missing IDEA nio-fs.jar: ${idea_home}/lib/nio-fs.jar"
[[ -f "${idea_home}/modules/module-descriptors.dat" ]] || die "Missing IDEA module descriptors: ${idea_home}/modules/module-descriptors.dat"
[[ -d "${idea_home}/plugins/kast-headless" ]] || die "Missing bundled kast-headless plugin"

if [[ -d "${backend_root}/libs" ]] && find "${backend_root}/libs" -name '*-all.jar' -print -quit | grep -q .; then
  find "${backend_root}/libs" -name '*-all.jar' -print >&2
  die "Devin headless runtime must not contain fat jars"
fi

grep -Fq "[backends.headless]" "$config_file" || die "config.toml does not include headless backend config"
grep -Fq "[runtime]" "$config_file" || die "config.toml does not include runtime config"
grep -Fq 'defaultBackend = "headless"' "$config_file" || die "config.toml does not default to headless runtime"
grep -Fq "runtimeLibsDir = \"${runtime_libs}\"" "$config_file" || die "config.toml does not point at bundled runtime libs"
grep -Fq "ideaHome = \"${idea_home}\"" "$config_file" || die "config.toml does not point at bundled IDEA home"
grep -Fq "binaryPath = \"${cli_path}\"" "$config_file" || die "config.toml does not point at bundled CLI"

doctor_output="$(KAST_CONFIG_HOME="$prefix" "$cli_path" --output json doctor)"
python3 - "$doctor_output" <<'PY'
import json
import sys

try:
    payload = json.loads(sys.argv[1])
except json.JSONDecodeError as error:
    raise SystemExit(f"kast doctor did not return JSON: {error}")
if payload.get("ok") is not True:
    raise SystemExit(f"kast doctor did not report ok=true: {payload}")
PY

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-runtime-verify.XXXXXX")"
workspace_root="${tmp_dir}/workspace"
mkdir -p "$workspace_root"

up_output="$(KAST_CONFIG_HOME="$prefix" "$cli_path" --output json up --workspace-root "$workspace_root")"
python3 - "$up_output" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
selected = payload.get("selected") or {}
descriptor = selected.get("descriptor") or {}
runtime_status = selected.get("runtimeStatus") or {}
backend = runtime_status.get("backendName") or descriptor.get("backendName")
if backend != "headless":
    raise SystemExit(f"kast up did not select headless backend: {payload}")
PY

rpc_output="$(KAST_CONFIG_HOME="$prefix" "$cli_path" rpc '{"jsonrpc":"2.0","method":"runtime/status","id":1}' --workspace-root "$workspace_root")"
python3 - "$rpc_output" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
result = payload.get("result") or {}
if result.get("backendName") != "headless":
    raise SystemExit(f"kast rpc runtime/status did not use headless backend: {payload}")
PY

KAST_CONFIG_HOME="$prefix" "$cli_path" stop --workspace-root "$workspace_root" >/dev/null
workspace_root=""

printf '%s\n' "Verified Kast Devin headless runtime in ${prefix}"
