#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
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
  die "Neither sha256sum nor shasum is available"
}

zip_dir() {
  local output_path="$1"
  local input_dir="$2"
  python3 - "$output_path" "$input_dir" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

output_path = Path(sys.argv[1])
input_dir = Path(sys.argv[2])
with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(input_dir.rglob("*")):
        if path.is_dir():
            continue
        info = zipfile.ZipInfo(str(path.relative_to(input_dir)))
        mode = path.stat().st_mode
        info.external_attr = (stat.S_IFREG | (mode & 0o777)) << 16
        archive.writestr(info, path.read_bytes())
PY
}

expect_failure_contains() {
  local expected="$1"
  shift
  local output_path="${scratch_dir}/expected-failure.out"

  set +e
  "$@" >"$output_path" 2>&1
  local exit_code="$?"
  set -e

  [[ "$exit_code" -ne 0 ]] || die "Command unexpectedly succeeded: $*"
  grep -Fq "$expected" "$output_path" || {
    cat "$output_path" >&2
    die "Expected failure output to contain: $expected"
  }
}

repo_root="$(resolve_repo_root)"
packager="${repo_root}/scripts/package-devin-headless-runtime.sh"

need_tool python3
need_tool tar

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-headless-smoke.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

version="v9.8.7"
artifact_dir="${scratch_dir}/artifacts"
cli_tree="${scratch_dir}/cli"
backend_tree="${scratch_dir}/backend"
backend_with_fat_tree="${scratch_dir}/backend-with-fat"
backend_mismatch_tree="${scratch_dir}/backend-mismatch"
extract_dir="${scratch_dir}/extract"

mkdir -p \
  "$artifact_dir" \
  "$cli_tree" \
  "${backend_tree}/backend-headless/runtime-libs" \
  "${backend_tree}/backend-headless/idea-home/lib" \
  "${backend_tree}/backend-headless/idea-home/modules" \
  "${backend_tree}/backend-headless/idea-home/plugins/kast-headless/lib" \
  "$extract_dir"

cat > "${cli_tree}/kast" <<'FAKE_KAST'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--output" ]]; then
  [[ "${2:-}" == "json" ]] || { echo "unexpected --output value: ${2:-}" >&2; exit 1; }
  shift 2
fi

case "${1:-help}" in
  doctor)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    [[ -f "${KAST_CONFIG_HOME}/config.toml" ]] || { echo "missing config.toml" >&2; exit 1; }
    grep -Fq "[runtime]" "${KAST_CONFIG_HOME}/config.toml"
    grep -Fq 'defaultBackend = "headless"' "${KAST_CONFIG_HOME}/config.toml"
    grep -Fq "[backends.headless]" "${KAST_CONFIG_HOME}/config.toml"
    grep -Fq "runtimeLibsDir" "${KAST_CONFIG_HOME}/config.toml"
    grep -Fq "ideaHome" "${KAST_CONFIG_HOME}/config.toml"
    printf '%s\n' '{"ok":true}'
    ;;
  up)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    seen_wait_timeout=false
    seen_accept_indexing=false
    for arg in "$@"; do
      case "$arg" in
        --backend|--backend=*)
          echo "verify script must not pass --backend to up" >&2
          exit 1
          ;;
        --wait-timeout-ms=180000)
          seen_wait_timeout=true
          ;;
        --accept-indexing=true)
          seen_accept_indexing=true
          ;;
      esac
    done
    [[ "$seen_wait_timeout" == true ]] || { echo "verify script must pass --wait-timeout-ms=180000 to up" >&2; exit 1; }
    [[ "$seen_accept_indexing" == true ]] || { echo "verify script must pass --accept-indexing=true to up" >&2; exit 1; }
    grep -Fq 'defaultBackend = "headless"' "${KAST_CONFIG_HOME}/config.toml"
    touch "${KAST_CONFIG_HOME}/up-called"
    printf '%s\n' '{"selected":{"descriptor":{"backendName":"headless"},"runtimeStatus":{"backendName":"headless","state":"INDEXING","indexing":true}}}'
    ;;
  rpc)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    for arg in "$@"; do
      case "$arg" in
        --backend|--backend=*)
          echo "verify script must not pass --backend to rpc" >&2
          exit 1
          ;;
      esac
    done
    grep -Fq 'defaultBackend = "headless"' "${KAST_CONFIG_HOME}/config.toml"
    [[ "${2:-}" == *'"method":"runtime/status"'* ]] || { echo "unexpected rpc request: ${2:-}" >&2; exit 1; }
    touch "${KAST_CONFIG_HOME}/rpc-called"
    printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"backendName":"headless"}}'
    ;;
  stop)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    for arg in "$@"; do
      case "$arg" in
        --backend|--backend=*)
          echo "verify script must not pass --backend to stop" >&2
          exit 1
          ;;
      esac
    done
    touch "${KAST_CONFIG_HOME}/stop-called"
    printf '%s\n' '{"stopped":true}'
    ;;
  version|--version)
    printf '%s\n' "Kast CLI 9.8.7"
    ;;
  *)
    printf '%s\n' "fake kast"
    ;;
esac
FAKE_KAST
chmod 755 "${cli_tree}/kast"

cat > "${backend_tree}/backend-headless/kast-headless" <<'FAKE_BACKEND'
#!/usr/bin/env bash
printf '%s\n' "fake headless backend"
FAKE_BACKEND
chmod 755 "${backend_tree}/backend-headless/kast-headless"
printf '%s\n' "fake nio fs" > "${backend_tree}/backend-headless/idea-home/lib/nio-fs.jar"
printf '%s\n' "fake module descriptors" > "${backend_tree}/backend-headless/idea-home/modules/module-descriptors.dat"
printf '%s\n' "backend-headless-${version#v}-launcher.jar" > "${backend_tree}/backend-headless/runtime-libs/classpath.txt"
printf '%s\n' "fake launcher lib" > "${backend_tree}/backend-headless/runtime-libs/backend-headless-${version#v}-launcher.jar"
printf '%s\n' "fake plugin lib" > "${backend_tree}/backend-headless/idea-home/plugins/kast-headless/lib/backend-headless-${version#v}-plugin.jar"

cp -R "$backend_tree" "$backend_with_fat_tree"
mkdir -p "${backend_with_fat_tree}/backend-headless/libs"
printf '%s\n' "fat jar" > "${backend_with_fat_tree}/backend-headless/libs/backend-headless-9.8.7-all.jar"

cp -R "$backend_tree" "$backend_mismatch_tree"
rm -f "${backend_mismatch_tree}/backend-headless/runtime-libs/backend-headless-${version#v}-launcher.jar"
rm -f "${backend_mismatch_tree}/backend-headless/idea-home/plugins/kast-headless/lib/backend-headless-${version#v}-plugin.jar"
printf '%s\n' "backend-headless-1.2.3-launcher.jar" > "${backend_mismatch_tree}/backend-headless/runtime-libs/classpath.txt"
printf '%s\n' "fake stale launcher lib" > "${backend_mismatch_tree}/backend-headless/runtime-libs/backend-headless-1.2.3-launcher.jar"
printf '%s\n' "fake stale plugin lib" > "${backend_mismatch_tree}/backend-headless/idea-home/plugins/kast-headless/lib/backend-headless-1.2.3-plugin.jar"

cli_zip="${artifact_dir}/kast-${version}-linux-x64.zip"
backend_zip="${artifact_dir}/backend-headless-${version}.zip"
backend_with_fat_zip="${artifact_dir}/backend-headless-with-fat-${version}.zip"
backend_mismatch_zip="${artifact_dir}/backend-headless-stale-${version}.zip"
bundle_path="${artifact_dir}/kast-devin-headless-runtime-linux-x64-${version}.tar.gz"
zip_dir "$cli_zip" "$cli_tree"
zip_dir "$backend_zip" "$backend_tree"
zip_dir "$backend_with_fat_zip" "$backend_with_fat_tree"
zip_dir "$backend_mismatch_zip" "$backend_mismatch_tree"

expect_failure_contains \
  "must not contain fat jars" \
  "$packager" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_with_fat_zip" \
  --version "$version" \
  --output "${artifact_dir}/must-not-exist.tar.gz"

expect_failure_contains \
  "does not match requested version ${version}" \
  "$packager" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_mismatch_zip" \
  --version "$version" \
  --output "${artifact_dir}/must-not-exist-stale.tar.gz"

"$packager" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_zip" \
  --version "$version" \
  --output "$bundle_path"

[[ -f "$bundle_path" ]] || die "Bundle tarball was not created: $bundle_path"
[[ -f "${bundle_path}.sha256" ]] || die "Bundle SHA-256 sidecar was not created"
grep -Fq "$(basename -- "$bundle_path")" "${bundle_path}.sha256" || die "SHA-256 sidecar does not name the bundle"

tar -xzf "$bundle_path" -C "$extract_dir"
bundle_root="${extract_dir}/kast-devin-headless-runtime-linux-x64-${version}"
bundle_root="$(cd -- "$bundle_root" >/dev/null 2>&1 && pwd)"
backend_root="${bundle_root}/lib/backends/headless-${version}"
config_file="${bundle_root}/config.toml"

[[ -x "${bundle_root}/bin/kast" ]] || die "Bundle missing executable Rust CLI"
[[ -x "${backend_root}/kast-headless" ]] || die "Bundle missing headless launcher"
[[ -f "${backend_root}/runtime-libs/classpath.txt" ]] || die "Bundle missing runtime classpath"
[[ -f "${backend_root}/idea-home/lib/nio-fs.jar" ]] || die "Bundle missing IDEA home"
[[ -f "${backend_root}/idea-home/modules/module-descriptors.dat" ]] || die "Bundle missing module descriptors"
[[ -d "${backend_root}/idea-home/plugins/kast-headless" ]] || die "Bundle missing headless plugin"
[[ -x "${bundle_root}/scripts/setup-kast-devin-runtime.sh" ]] || die "Bundle missing setup script"
[[ -x "${bundle_root}/scripts/verify-kast-devin-runtime.sh" ]] || die "Bundle missing verify script"
[[ -f "${bundle_root}/SETUP.md" ]] || die "Bundle missing SETUP.md"
[[ -f "${bundle_root}/manifest.json" ]] || die "Bundle missing manifest"
[[ ! -e "$config_file" ]] || die "Bundle config.toml must be generated by setup, not baked into the archive"

python3 - "${bundle_root}/manifest.json" "$version" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
version = sys.argv[2]
assert payload["schemaVersion"] == 1, payload
assert payload["kind"] == "KAST_DEVIN_HEADLESS_RUNTIME", payload
assert payload["version"] == version, payload
assert payload["platform"] == "devin-headless-linux-x64", payload
assert payload["backendInstallName"] == f"headless-{version}", payload
roles = {entry["role"] for entry in payload["artifacts"]}
assert {"cli", "headless-backend"}.issubset(roles), payload
PY

"${bundle_root}/scripts/setup-kast-devin-runtime.sh" --prefix "$bundle_root"

[[ -f "$config_file" ]] || die "Setup did not write config.toml"
grep -Fq "[runtime]" "$config_file" || die "config.toml does not include runtime config"
grep -Fq 'defaultBackend = "headless"' "$config_file" || die "config.toml does not default to headless runtime"
grep -Fq "[backends.headless]" "$config_file" || die "config.toml does not include headless backend config"
grep -Fq "runtimeLibsDir = \"${backend_root}/runtime-libs\"" "$config_file" \
  || die "config.toml does not point at bundled runtime libs"
grep -Fq "ideaHome = \"${backend_root}/idea-home\"" "$config_file" \
  || die "config.toml does not point at bundled IDEA home"
grep -Fq "binaryPath = \"${bundle_root}/bin/kast\"" "$config_file" \
  || die "config.toml does not point at bundled CLI"
grep -Fq "version = \"9.8.7\"" "$config_file" || die "config.toml does not normalize install version"

invalid_timeout_log="${scratch_dir}/invalid-timeout.log"
if KAST_DEVIN_RUNTIME_WAIT_TIMEOUT_MS=not-a-number "${bundle_root}/scripts/verify-kast-devin-runtime.sh" --prefix "$bundle_root" >"$invalid_timeout_log" 2>&1; then
  die "verify script accepted a non-numeric KAST_DEVIN_RUNTIME_WAIT_TIMEOUT_MS"
fi
grep -Fq "KAST_DEVIN_RUNTIME_WAIT_TIMEOUT_MS must be numeric" "$invalid_timeout_log" \
  || die "verify script did not explain invalid KAST_DEVIN_RUNTIME_WAIT_TIMEOUT_MS"

"${bundle_root}/scripts/verify-kast-devin-runtime.sh" --prefix "$bundle_root"
[[ -f "${bundle_root}/up-called" ]] || die "verify script did not run kast up"
[[ -f "${bundle_root}/rpc-called" ]] || die "verify script did not run kast rpc"
[[ -f "${bundle_root}/stop-called" ]] || die "verify script did not stop the runtime"

expected_digest="$(compute_sha256 "$bundle_path")"
actual_digest="$(awk '{ print $1 }' "${bundle_path}.sha256")"
[[ "$actual_digest" == "$expected_digest" ]] || die "SHA-256 sidecar digest mismatch"

printf '%s\n' "Devin headless runtime smoke test passed"
