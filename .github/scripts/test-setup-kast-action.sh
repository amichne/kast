#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
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

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

tar_zstd_create() {
  local output="$1"
  local root="$2"
  tar --zstd -C "$root" -cf "$output" .
}

write_fixture_cli() {
  local output="$1"
  mkdir -p "$(dirname -- "$output")"
  cat > "$output" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  --version|version)
    printf '%s\n' 'Kast CLI 9.8.7'
    ;;
  doctor)
    printf '%s\n' 'doctor ok'
    ;;
  *)
    printf 'fixture kast %s\n' "$*"
    ;;
esac
SH
  chmod 755 "$output"
}

write_failing_doctor_cli() {
  local output="$1"
  mkdir -p "$(dirname -- "$output")"
  cat > "$output" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  --version|version)
    printf '%s\n' 'Kast CLI 9.8.7'
    ;;
  doctor)
    printf '%s\n' 'doctor failed' >&2
    exit 7
    ;;
  *)
    printf 'fixture kast %s\n' "$*"
    ;;
esac
SH
  chmod 755 "$output"
}

write_runtime_fixture() {
  local output_dir="$1"
  local cli_mode="${2:-ok}"
  rm -rf "$output_dir"
  mkdir -p \
    "${output_dir}/runtime/bin" \
    "${output_dir}/runtime/lib/runtime-libs" \
    "${output_dir}/runtime/idea/lib" \
    "${output_dir}/runtime/idea/modules" \
    "${output_dir}/runtime/idea/plugins/kast-headless/lib" \
    "${output_dir}/runtime/plugins/kast-headless/lib"
  if [[ "$cli_mode" == "failing-doctor" ]]; then
    write_failing_doctor_cli "${output_dir}/runtime/bin/kast"
  else
    write_fixture_cli "${output_dir}/runtime/bin/kast"
  fi
  printf '%s\n' 'fixture-classpath' > "${output_dir}/runtime/lib/runtime-libs/classpath.txt"
  : > "${output_dir}/runtime/idea/lib/nio-fs.jar"
  : > "${output_dir}/runtime/idea/modules/module-descriptors.dat"
  : > "${output_dir}/runtime/idea/plugins/kast-headless/lib/backend.jar"
  : > "${output_dir}/runtime/plugins/kast-headless/lib/backend.jar"
}

write_gradle_cache_fixture() {
  local output_dir="$1"
  rm -rf "$output_dir"
  mkdir -p "${output_dir}/gradle-ro/modules-2/files-2.1/example/module"
  printf '%s\n' 'fixture' > "${output_dir}/gradle-ro/modules-2/files-2.1/example/module/artifact.pom"
}

assert_tree_read_only() {
  local root="$1"
  python3 - "$root" <<'PY'
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in [root, *root.rglob("*")]:
    mode = path.lstat().st_mode
    if mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH):
        raise SystemExit(f"path is writable: {path}")
PY
}

write_manifest() {
  local output_path="$1"
  local artifact_sha="$2"
  local schema_version="${3:-1}"
  local version="${4:-9.8.7}"
  python3 - "$output_path" "$artifact_sha" "$schema_version" "$version" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
artifact_sha = sys.argv[2]
schema_version = int(sys.argv[3])
version = sys.argv[4]
payload = {
    "schemaVersion": schema_version,
    "kastVersion": version,
    "kastGitSha": "0123456789abcdef",
    "os": "linux",
    "arch": "x64",
    "javaVersion": "21",
    "intellijBuild": "2025.3",
    "kotlinPluginVersion": "2.3.21",
    "kastIndexSchemaVersion": "7",
    "artifactSha256": artifact_sha,
}
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

write_fake_sudo() {
  local output_path="$1"
  mkdir -p "$(dirname -- "$output_path")"
  cat > "$output_path" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-n" && "${2:-}" == "true" ]]; then
  exit 0
fi
if [[ -n "${FAKE_SUDO_UNLOCK_DIR:-}" ]]; then
  chmod 755 "$FAKE_SUDO_UNLOCK_DIR"
fi
exec "$@"
SH
  chmod 755 "$output_path"
}

write_tool_symlink() {
  local tool="$1"
  local output_dir="$2"
  local source_path
  source_path="$(command -v "$tool")" || die "Missing host tool for test symlink: $tool"
  mkdir -p "$output_dir"
  ln -sf "$source_path" "${output_dir}/${tool}"
}

run_action() {
  local scratch="$1"
  shift
  local env_file="${scratch}/github-env"
  local path_file="${scratch}/github-path"
  : > "$env_file"
  : > "$path_file"
  env \
    RUNNER_OS="${RUNNER_OS:-Linux}" \
    RUNNER_ARCH="${RUNNER_ARCH:-X64}" \
    HOME="${scratch}/home" \
    KAST_CACHE_HOME="${scratch}/home/.cache/kast" \
  KAST_CONFIG_HOME="${scratch}/home/.config/kast" \
  GITHUB_ENV="$env_file" \
  GITHUB_PATH="$path_file" \
  PATH="${PATH}" \
  "$@" \
  node "${repo_root}/setup-kast/dist/index.js"
}

repo_root="$(resolve_repo_root)"
action_dist="${repo_root}/setup-kast/dist/index.js"
[[ -f "$action_dist" ]] || die "setup-kast action dist is missing: $action_dist"

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required tool: $1"
}

need_tool node
need_tool tar
need_tool zstd

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-setup-action.XXXXXX")"
scratch_dir="$(cd -- "$scratch_dir" && pwd)"
retry_server_pid=""
cleanup() {
  if [[ -n "${retry_server_pid:-}" ]]; then
    kill "$retry_server_pid" >/dev/null 2>&1 || true
  fi
  chmod -R u+w "$scratch_dir" >/dev/null 2>&1 || true
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

runtime_fixture="${scratch_dir}/runtime-fixture"
write_runtime_fixture "$runtime_fixture"
runtime_artifact="${scratch_dir}/kast-headless-linux-x64.tar.zst"
tar_zstd_create "$runtime_artifact" "${runtime_fixture}/runtime"
runtime_sha="$(compute_sha256 "$runtime_artifact")"
runtime_manifest="${scratch_dir}/kast-runtime-manifest.json"
write_manifest "$runtime_manifest" "$runtime_sha"

cache_fixture="${scratch_dir}/cache-fixture"
write_gradle_cache_fixture "$cache_fixture"
cache_artifact="${scratch_dir}/gradle-ro-dep-cache.tar.zst"
tar_zstd_create "$cache_artifact" "$cache_fixture"
cache_sha="$(compute_sha256 "$cache_artifact")"

missing_zstd_root="${scratch_dir}/missing-zstd"
missing_zstd_bin="${missing_zstd_root}/bin"
mkdir -p "$missing_zstd_root/home"
write_tool_symlink node "$missing_zstd_bin"
write_tool_symlink tar "$missing_zstd_bin"
if run_action "$missing_zstd_root" \
  PATH="$missing_zstd_bin" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${missing_zstd_root}/opt/kast" \
  >"${missing_zstd_root}/out" 2>"${missing_zstd_root}/err"; then
  die "setup-kast unexpectedly succeeded without zstd on PATH"
fi
require_contains "${missing_zstd_root}/err" "missing required tool 'zstd'" "missing zstd preflight failure"

unsafe_version_root="${scratch_dir}/unsafe-version"
mkdir -p "$unsafe_version_root/home"
if run_action "$unsafe_version_root" \
  "INPUT_VERSION=../escape" \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${unsafe_version_root}/opt/kast" \
  >"${unsafe_version_root}/out" 2>"${unsafe_version_root}/err"; then
  die "setup-kast unexpectedly accepted an unsafe version path segment"
fi
require_contains "${unsafe_version_root}/err" "version must be a semver path segment" "unsafe version failure"
[[ ! -e "${unsafe_version_root}/opt/kast/current" ]] || die "unsafe version created current symlink"

bad_shape_fixture="${scratch_dir}/bad-runtime-shape-fixture"
rm -rf "$bad_shape_fixture"
mkdir -p \
  "${bad_shape_fixture}/runtime/bin/kast" \
  "${bad_shape_fixture}/runtime/lib/runtime-libs" \
  "${bad_shape_fixture}/runtime/idea/modules"
printf '%s\n' 'fixture-classpath' > "${bad_shape_fixture}/runtime/lib/runtime-libs/classpath.txt"
: > "${bad_shape_fixture}/runtime/idea/modules/module-descriptors.dat"
bad_shape_artifact="${scratch_dir}/kast-headless-linux-x64-bad-shape.tar.zst"
tar_zstd_create "$bad_shape_artifact" "${bad_shape_fixture}/runtime"
bad_shape_sha="$(compute_sha256 "$bad_shape_artifact")"
bad_shape_manifest="${scratch_dir}/kast-runtime-manifest-bad-shape.json"
write_manifest "$bad_shape_manifest" "$bad_shape_sha"
bad_shape_root="${scratch_dir}/bad-runtime-shape"
mkdir -p "$bad_shape_root/home"
if run_action "$bad_shape_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${bad_shape_artifact}" \
  "INPUT_ARTIFACT-SHA256=${bad_shape_sha}" \
  "INPUT_MANIFEST-URL=file://${bad_shape_manifest}" \
  "INPUT_INSTALL-DIR=${bad_shape_root}/opt/kast" \
  >"${bad_shape_root}/out" 2>"${bad_shape_root}/err"; then
  die "runtime archive with directory bin/kast unexpectedly succeeded"
fi
require_contains "${bad_shape_root}/err" "regular files bin/kast" "bad runtime shape failure"
[[ ! -e "${bad_shape_root}/opt/kast/current" ]] || die "bad runtime shape created current symlink"

success_root="${scratch_dir}/success"
mkdir -p "$success_root/home"
run_action "$success_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${success_root}/opt/kast" \
  "INPUT_GRADLE-RO-CACHE-URL=file://${cache_artifact}" \
  "INPUT_GRADLE-RO-CACHE-SHA256=${cache_sha}" \
  "INPUT_FAIL-ON-CACHE-MISS=false"

[[ -L "${success_root}/opt/kast/current" ]] || die "current symlink was not created"
[[ -x "${success_root}/opt/kast/current/bin/kast" ]] || die "installed kast binary is missing"
[[ -f "${success_root}/opt/kast/current/kast-runtime-manifest.json" ]] || die "installed manifest is missing"
[[ -d "${success_root}/opt/kast/cache/gradle-ro/modules-2" ]] || die "Gradle read-only cache was not installed"
[[ -f "${success_root}/home/.config/kast/config.toml" ]] || die "Kast config was not written"
[[ -d "${success_root}/home/.cache/kast" ]] || die "Kast cache home was not created"
[[ -d "${success_root}/home/.gradle" ]] || die "Writable Gradle user home was not created"
assert_tree_read_only "${success_root}/opt/kast/cache/gradle-ro"
require_contains "${success_root}/github-path" "${success_root}/opt/kast/current/bin" "GITHUB_PATH"
require_contains "${success_root}/github-env" "KAST_HOME<<" "GITHUB_ENV KAST_HOME"
require_contains "${success_root}/github-env" "${success_root}/opt/kast/current" "GITHUB_ENV KAST_HOME value"
require_contains "${success_root}/github-env" "KAST_CACHE_HOME<<" "GITHUB_ENV KAST_CACHE_HOME"
require_contains "${success_root}/github-env" "${success_root}/home/.cache/kast" "GITHUB_ENV KAST_CACHE_HOME value"
require_contains "${success_root}/github-env" "GRADLE_RO_DEP_CACHE<<" "GITHUB_ENV Gradle cache"
require_contains "${success_root}/github-env" "${success_root}/opt/kast/cache/gradle-ro" "GITHUB_ENV Gradle cache value"
require_contains "${success_root}/home/.config/kast/config.toml" "defaultBackend = \"headless\"" "setup config"
require_contains "${success_root}/home/.config/kast/config.toml" "runtimeLibsDir = \"${success_root}/opt/kast/current/lib/runtime-libs\"" "setup config runtime libs"
require_contains "${success_root}/home/.config/kast/config.toml" "descriptorDir = \"${success_root}/home/.cache/kast/workspaces\"" "setup config descriptor dir"

PATH="${success_root}/opt/kast/current/bin:${PATH}" \
KAST_HOME="${success_root}/opt/kast/current" \
KAST_CACHE_HOME="${success_root}/home/.cache/kast" \
GRADLE_RO_DEP_CACHE="${success_root}/opt/kast/cache/gradle-ro" \
GRADLE_USER_HOME="${success_root}/home/.gradle" \
  "${repo_root}/scripts/verify-setup-kast-install.sh" --skip-daemon

gradle_warm_root="${success_root}/gradle-warm"
gradle_warm_log="${success_root}/gradle-warm.log"
mkdir -p "$gradle_warm_root"
cat > "${gradle_warm_root}/gradlew" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
: "${GRADLE_RO_DEP_CACHE:?}"
: "${GRADLE_USER_HOME:?}"
: "${GRADLE_WARM_LOG:?}"
test -d "${GRADLE_RO_DEP_CACHE}/modules-2"
test -w "$GRADLE_USER_HOME"
printf '%s\n' "$*" >> "$GRADLE_WARM_LOG"
case "${1:-}" in
  --version|dependencies|buildEnvironment)
    exit 0
    ;;
  *)
    printf 'unexpected gradle warm command: %s\n' "$*" >&2
    exit 2
    ;;
esac
SH
chmod 755 "${gradle_warm_root}/gradlew"
PATH="${success_root}/opt/kast/current/bin:${PATH}" \
KAST_HOME="${success_root}/opt/kast/current" \
KAST_CACHE_HOME="${success_root}/home/.cache/kast" \
GRADLE_RO_DEP_CACHE="${success_root}/opt/kast/cache/gradle-ro" \
GRADLE_USER_HOME="${success_root}/home/.gradle" \
GRADLE_WARM_LOG="$gradle_warm_log" \
  "${repo_root}/scripts/verify-setup-kast-install.sh" \
    --skip-daemon \
    --gradle-root "$gradle_warm_root"
require_contains "$gradle_warm_log" "--version --no-daemon" "Gradle warm verifier"
require_contains "$gradle_warm_log" "dependencies --no-daemon" "Gradle warm verifier"
require_contains "$gradle_warm_log" "buildEnvironment --no-daemon" "Gradle warm verifier"

stale_path_root="${scratch_dir}/stale-path"
mkdir -p "${stale_path_root}/bin"
write_fixture_cli "${stale_path_root}/bin/kast"
if PATH="${stale_path_root}/bin:${success_root}/opt/kast/current/bin:${PATH}" \
KAST_HOME="${success_root}/opt/kast/current" \
KAST_CACHE_HOME="${success_root}/home/.cache/kast" \
GRADLE_RO_DEP_CACHE="${success_root}/opt/kast/cache/gradle-ro" \
GRADLE_USER_HOME="${success_root}/home/.gradle" \
  "${repo_root}/scripts/verify-setup-kast-install.sh" --skip-daemon \
  >"${stale_path_root}/out" 2>"${stale_path_root}/err"; then
  die "setup-kast verifier unexpectedly accepted stale kast earlier on PATH"
fi
require_contains "${stale_path_root}/err" "kast on PATH does not match install-dir" "stale PATH verifier failure"

run_action "$success_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${success_root}/opt/kast" \
  "INPUT_GRADLE-RO-CACHE-URL=file://${cache_artifact}" \
  "INPUT_GRADLE-RO-CACHE-SHA256=${cache_sha}" \
  "INPUT_FAIL-ON-CACHE-MISS=false" \
  >"${success_root}/reinstall-out" 2>"${success_root}/reinstall-err"
[[ -d "${success_root}/opt/kast/cache/gradle-ro/modules-2" ]] || die "Gradle read-only cache was not reinstalled"
assert_tree_read_only "${success_root}/opt/kast/cache/gradle-ro"

retry_server_script="${scratch_dir}/retry-server.py"
cat > "$retry_server_script" <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
from urllib.parse import urlsplit

runtime_path = Path(sys.argv[1])
manifest_path = Path(sys.argv[2])
cache_path = Path(sys.argv[3])
port_file = Path(sys.argv[4])
counter_file = Path(sys.argv[5])

class Handler(BaseHTTPRequestHandler):
    def is_authorized(self, expected):
        if self.headers.get("Authorization") == expected:
            return True
        self.send_response(401)
        self.end_headers()
        self.wfile.write(b"unauthorized")
        return False

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/runtime.tar.zst":
            count = int(counter_file.read_text() if counter_file.exists() else "0") + 1
            counter_file.write_text(str(count))
            if count == 1:
                self.send_response(503)
                self.end_headers()
                self.wfile.write(b"transient failure")
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(runtime_path.read_bytes())
            return
        if path == "/manifest.json":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(manifest_path.read_bytes())
            return
        if path == "/authorized-runtime.tar.zst":
            if not self.is_authorized("Bearer runtime-token"):
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(runtime_path.read_bytes())
            return
        if path == "/authorized-manifest.json":
            if not self.is_authorized("Bearer runtime-token"):
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(manifest_path.read_bytes())
            return
        if path == "/authorized-cache.tar.zst":
            if not self.is_authorized("Bearer cache-token"):
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(cache_path.read_bytes())
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        pass

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_address[1]))
server.serve_forever()
PY
retry_port_file="${scratch_dir}/retry-server.port"
retry_counter_file="${scratch_dir}/retry-server.count"
python3 "$retry_server_script" "$runtime_artifact" "$runtime_manifest" "$cache_artifact" "$retry_port_file" "$retry_counter_file" &
retry_server_pid="$!"
for _ in {1..50}; do
  [[ -f "$retry_port_file" ]] && break
  sleep 0.1
done
[[ -f "$retry_port_file" ]] || die "retry HTTP server did not start"
retry_port="$(cat "$retry_port_file")"
retry_root="${scratch_dir}/retry-download"
mkdir -p "$retry_root/home"
run_action "$retry_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=http://127.0.0.1:${retry_port}/runtime.tar.zst" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=http://127.0.0.1:${retry_port}/manifest.json" \
  "INPUT_INSTALL-DIR=${retry_root}/opt/kast" \
  >"${retry_root}/out" 2>"${retry_root}/err"
[[ "$(cat "$retry_counter_file")" == "2" ]] || die "runtime download was not retried after transient failure"
[[ -x "${retry_root}/opt/kast/current/bin/kast" ]] || die "retry download did not install kast binary"

auth_root="${scratch_dir}/authorized-download"
mkdir -p "$auth_root/home"
run_action "$auth_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=http://127.0.0.1:${retry_port}/authorized-runtime.tar.zst" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=http://127.0.0.1:${retry_port}/authorized-manifest.json" \
  "INPUT_AUTHORIZATION-HEADER=Bearer runtime-token" \
  "INPUT_GRADLE-RO-CACHE-URL=http://127.0.0.1:${retry_port}/authorized-cache.tar.zst" \
  "INPUT_GRADLE-RO-CACHE-SHA256=${cache_sha}" \
  "INPUT_GRADLE-RO-CACHE-AUTHORIZATION-HEADER=Bearer cache-token" \
  "INPUT_FAIL-ON-CACHE-MISS=true" \
  "INPUT_INSTALL-DIR=${auth_root}/opt/kast" \
  >"${auth_root}/out" 2>"${auth_root}/err"
[[ -x "${auth_root}/opt/kast/current/bin/kast" ]] || die "authorized download did not install kast binary"
[[ -d "${auth_root}/opt/kast/cache/gradle-ro/modules-2" ]] || die "authorized download did not install Gradle cache"
[[ -d "${auth_root}/home/.cache/kast" ]] || die "authorized download did not create Kast cache home"
[[ -d "${auth_root}/home/.gradle" ]] || die "authorized download did not create writable Gradle user home"

redaction_root="${scratch_dir}/redacted-download-failure"
mkdir -p "$redaction_root/home"
if run_action "$redaction_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=http://127.0.0.1:${retry_port}/missing-runtime.tar.zst?token=topsecret&signature=alsosecret" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=http://127.0.0.1:${retry_port}/manifest.json" \
  "INPUT_INSTALL-DIR=${redaction_root}/opt/kast" \
  "INPUT_DOWNLOAD-ATTEMPTS=1" \
  >"${redaction_root}/out" 2>"${redaction_root}/err"; then
  die "missing signed runtime URL unexpectedly succeeded"
fi
require_contains "${redaction_root}/err" "download failed for runtime artifact" "redacted download failure"
if grep -Fq "topsecret" "${redaction_root}/err" || grep -Fq "alsosecret" "${redaction_root}/err"; then
  die "download failure leaked signed URL query parameters"
fi

fake_sudo_root="${scratch_dir}/fake-sudo"
fake_sudo_bin="${fake_sudo_root}/bin"
write_fake_sudo "${fake_sudo_bin}/sudo"
mkdir -p "${fake_sudo_root}/home" "${fake_sudo_root}/locked"
chmod 555 "${fake_sudo_root}/locked"
run_action "$fake_sudo_root" \
  PATH="${fake_sudo_bin}:${PATH}" \
  "FAKE_SUDO_UNLOCK_DIR=${fake_sudo_root}/locked" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${fake_sudo_root}/locked/kast" \
  >"${fake_sudo_root}/out" 2>"${fake_sudo_root}/err"
chmod 755 "${fake_sudo_root}/locked"
[[ -L "${fake_sudo_root}/locked/kast/current" ]] || die "sudo fallback did not create current symlink"
[[ -x "${fake_sudo_root}/locked/kast/current/bin/kast" ]] || die "sudo fallback did not install kast binary"

bad_checksum_root="${scratch_dir}/bad-checksum"
mkdir -p "$bad_checksum_root/home"
if run_action "$bad_checksum_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  INPUT_ARTIFACT-SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${bad_checksum_root}/opt/kast" \
  >"${bad_checksum_root}/out" 2>"${bad_checksum_root}/err"; then
  die "bad runtime checksum unexpectedly succeeded"
fi
require_contains "${bad_checksum_root}/err" "checksum mismatch" "bad checksum failure"

unsafe_tar="${scratch_dir}/unsafe-runtime.tar"
unsafe_artifact="${scratch_dir}/unsafe-runtime.tar.zst"
python3 - "$unsafe_tar" <<'PY'
import io
import sys
import tarfile
from pathlib import Path

tar_path = Path(sys.argv[1])
with tarfile.open(tar_path, "w") as archive:
    payload = b"unsafe"
    info = tarfile.TarInfo("../escape")
    info.size = len(payload)
    archive.addfile(info, io.BytesIO(payload))
PY
zstd -q -f "$unsafe_tar" -o "$unsafe_artifact"
unsafe_sha="$(compute_sha256 "$unsafe_artifact")"
unsafe_manifest="${scratch_dir}/unsafe-manifest.json"
write_manifest "$unsafe_manifest" "$unsafe_sha"
unsafe_root="${scratch_dir}/unsafe"
mkdir -p "$unsafe_root/home"
if run_action "$unsafe_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${unsafe_artifact}" \
  "INPUT_ARTIFACT-SHA256=${unsafe_sha}" \
  "INPUT_MANIFEST-URL=file://${unsafe_manifest}" \
  "INPUT_INSTALL-DIR=${unsafe_root}/opt/kast" \
  >"${unsafe_root}/out" 2>"${unsafe_root}/err"; then
  die "unsafe tar archive unexpectedly succeeded"
fi
require_contains "${unsafe_root}/err" "unsafe archive member" "unsafe archive failure"
[[ ! -e "${scratch_dir}/escape" ]] || die "unsafe tar archive wrote outside extraction root"

missing_manifest_root="${scratch_dir}/missing-manifest"
mkdir -p "$missing_manifest_root/home"
if run_action "$missing_manifest_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_INSTALL-DIR=${missing_manifest_root}/opt/kast" \
  >"${missing_manifest_root}/out" 2>"${missing_manifest_root}/err"; then
  die "missing runtime manifest unexpectedly succeeded"
fi
require_contains "${missing_manifest_root}/err" "kast-runtime-manifest.json" "missing manifest failure"

bad_manifest_schema="${scratch_dir}/bad-manifest-schema.json"
write_manifest "$bad_manifest_schema" "$runtime_sha" 2
bad_schema_root="${scratch_dir}/bad-schema"
mkdir -p "$bad_schema_root/home"
if run_action "$bad_schema_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${bad_manifest_schema}" \
  "INPUT_INSTALL-DIR=${bad_schema_root}/opt/kast" \
  >"${bad_schema_root}/out" 2>"${bad_schema_root}/err"; then
  die "bad manifest schemaVersion unexpectedly succeeded"
fi
require_contains "${bad_schema_root}/err" "schemaVersion" "bad schemaVersion failure"

extra_manifest="${scratch_dir}/extra-manifest.json"
python3 - "$runtime_manifest" "$extra_manifest" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["unexpected"] = "not part of the contract"
Path(sys.argv[2]).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
extra_manifest_root="${scratch_dir}/extra-manifest"
mkdir -p "$extra_manifest_root/home"
if run_action "$extra_manifest_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${extra_manifest}" \
  "INPUT_INSTALL-DIR=${extra_manifest_root}/opt/kast" \
  >"${extra_manifest_root}/out" 2>"${extra_manifest_root}/err"; then
  die "manifest with unsupported field unexpectedly succeeded"
fi
require_contains "${extra_manifest_root}/err" "unsupported field" "extra manifest field failure"

numeric_index_manifest="${scratch_dir}/numeric-index-manifest.json"
python3 - "$runtime_manifest" "$numeric_index_manifest" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["kastIndexSchemaVersion"] = 7
Path(sys.argv[2]).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
numeric_index_root="${scratch_dir}/numeric-index"
mkdir -p "$numeric_index_root/home"
if run_action "$numeric_index_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${numeric_index_manifest}" \
  "INPUT_INSTALL-DIR=${numeric_index_root}/opt/kast" \
  >"${numeric_index_root}/out" 2>"${numeric_index_root}/err"; then
  die "numeric index schema manifest unexpectedly succeeded"
fi
require_contains "${numeric_index_root}/err" "kastIndexSchemaVersion" "numeric index schema failure"

unsupported_root="${scratch_dir}/unsupported"
mkdir -p "$unsupported_root/home"
if RUNNER_ARCH=ARM64 run_action "$unsupported_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${unsupported_root}/opt/kast" \
  >"${unsupported_root}/out" 2>"${unsupported_root}/err"; then
  die "unsupported architecture unexpectedly succeeded"
fi
require_contains "${unsupported_root}/err" "unsupported platform" "unsupported platform failure"

newline_input_root="${scratch_dir}/newline-input"
mkdir -p "$newline_input_root/home"
if run_action "$newline_input_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${newline_input_root}/opt"$'\n'"kast" \
  >"${newline_input_root}/out" 2>"${newline_input_root}/err"; then
  die "newline install-dir input unexpectedly succeeded"
fi
require_contains "${newline_input_root}/err" "line breaks" "newline input failure"

symlink_fixture="${scratch_dir}/symlink-fixture"
mkdir -p \
  "${symlink_fixture}/runtime/bin" \
  "${symlink_fixture}/runtime/lib/runtime-libs" \
  "${symlink_fixture}/runtime/idea/modules"
ln -s /bin/sh "${symlink_fixture}/runtime/bin/kast"
printf '%s\n' 'fixture-classpath' > "${symlink_fixture}/runtime/lib/runtime-libs/classpath.txt"
: > "${symlink_fixture}/runtime/idea/modules/module-descriptors.dat"
symlink_artifact="${scratch_dir}/kast-headless-linux-x64-symlink.tar.zst"
tar_zstd_create "$symlink_artifact" "${symlink_fixture}/runtime"
symlink_sha="$(compute_sha256 "$symlink_artifact")"
symlink_manifest="${scratch_dir}/kast-runtime-manifest-symlink.json"
write_manifest "$symlink_manifest" "$symlink_sha"
symlink_root="${scratch_dir}/symlink"
mkdir -p "$symlink_root/home"
if run_action "$symlink_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${symlink_artifact}" \
  "INPUT_ARTIFACT-SHA256=${symlink_sha}" \
  "INPUT_MANIFEST-URL=file://${symlink_manifest}" \
  "INPUT_INSTALL-DIR=${symlink_root}/opt/kast" \
  >"${symlink_root}/out" 2>"${symlink_root}/err"; then
  die "runtime archive with symlink unexpectedly succeeded"
fi
require_contains "${symlink_root}/err" "unsafe archive member type" "symlink archive failure"

hardlink_fixture="${scratch_dir}/hardlink-fixture"
mkdir -p \
  "${hardlink_fixture}/runtime/bin" \
  "${hardlink_fixture}/runtime/lib/runtime-libs" \
  "${hardlink_fixture}/runtime/idea/modules"
write_fixture_cli "${hardlink_fixture}/runtime/bin/kast-real"
ln "${hardlink_fixture}/runtime/bin/kast-real" "${hardlink_fixture}/runtime/bin/kast"
printf '%s\n' 'fixture-classpath' > "${hardlink_fixture}/runtime/lib/runtime-libs/classpath.txt"
: > "${hardlink_fixture}/runtime/idea/modules/module-descriptors.dat"
hardlink_artifact="${scratch_dir}/kast-headless-linux-x64-hardlink.tar.zst"
tar_zstd_create "$hardlink_artifact" "${hardlink_fixture}/runtime"
hardlink_sha="$(compute_sha256 "$hardlink_artifact")"
hardlink_manifest="${scratch_dir}/kast-runtime-manifest-hardlink.json"
write_manifest "$hardlink_manifest" "$hardlink_sha"
hardlink_root="${scratch_dir}/hardlink"
mkdir -p "$hardlink_root/home"
if run_action "$hardlink_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${hardlink_artifact}" \
  "INPUT_ARTIFACT-SHA256=${hardlink_sha}" \
  "INPUT_MANIFEST-URL=file://${hardlink_manifest}" \
  "INPUT_INSTALL-DIR=${hardlink_root}/opt/kast" \
  >"${hardlink_root}/out" 2>"${hardlink_root}/err"; then
  die "runtime archive with hardlink unexpectedly succeeded"
fi
require_contains "${hardlink_root}/err" "unsafe archive member type" "hardlink archive failure"

cache_miss_root="${scratch_dir}/cache-miss"
mkdir -p "$cache_miss_root/home"
run_action "$cache_miss_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${cache_miss_root}/opt/kast" \
  "INPUT_GRADLE-RO-CACHE-URL=file://${scratch_dir}/missing-cache.tar.zst" \
  INPUT_FAIL-ON-CACHE-MISS=false \
  >"${cache_miss_root}/out" 2>"${cache_miss_root}/err"
require_contains "${cache_miss_root}/err" "Gradle read-only cache was not installed" "cache miss warning"

cache_miss_strict_root="${scratch_dir}/cache-miss-strict"
mkdir -p "$cache_miss_strict_root/home"
if run_action "$cache_miss_strict_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${cache_miss_strict_root}/opt/kast" \
  "INPUT_GRADLE-RO-CACHE-URL=file://${scratch_dir}/missing-cache.tar.zst" \
  INPUT_FAIL-ON-CACHE-MISS=true \
  >"${cache_miss_strict_root}/out" 2>"${cache_miss_strict_root}/err"; then
  die "strict cache miss unexpectedly succeeded"
fi
require_contains "${cache_miss_strict_root}/err" "Gradle read-only cache was not installed" "strict cache miss failure"

bad_cache_shape_fixture="${scratch_dir}/bad-cache-shape-fixture"
rm -rf "$bad_cache_shape_fixture"
mkdir -p "${bad_cache_shape_fixture}/modules-2/files-2.1/example/module"
printf '%s\n' 'fixture' > "${bad_cache_shape_fixture}/modules-2/files-2.1/example/module/artifact.pom"
bad_cache_shape_artifact="${scratch_dir}/bad-cache-shape.tar.zst"
tar_zstd_create "$bad_cache_shape_artifact" "$bad_cache_shape_fixture"
bad_cache_shape_sha="$(compute_sha256 "$bad_cache_shape_artifact")"
bad_cache_shape_root="${scratch_dir}/bad-cache-shape"
mkdir -p "$bad_cache_shape_root/home"
if run_action "$bad_cache_shape_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${bad_cache_shape_root}/opt/kast" \
  "INPUT_GRADLE-RO-CACHE-URL=file://${bad_cache_shape_artifact}" \
  "INPUT_GRADLE-RO-CACHE-SHA256=${bad_cache_shape_sha}" \
  INPUT_FAIL-ON-CACHE-MISS=true \
  >"${bad_cache_shape_root}/out" 2>"${bad_cache_shape_root}/err"; then
  die "strict bad cache shape unexpectedly succeeded"
fi
require_contains "${bad_cache_shape_root}/err" "gradle-ro/modules-2" "bad cache shape failure"

bad_cache_metadata_fixture="${scratch_dir}/bad-cache-metadata-fixture"
rm -rf "$bad_cache_metadata_fixture"
mkdir -p "${bad_cache_metadata_fixture}/gradle-ro/modules-2/files-2.1/example/module"
printf '%s\n' 'fixture' > "${bad_cache_metadata_fixture}/gradle-ro/modules-2/files-2.1/example/module/artifact.pom"
printf '%s\n' 'lock' > "${bad_cache_metadata_fixture}/gradle-ro/modules-2/modules-2.lock"
printf '%s\n' 'gc' > "${bad_cache_metadata_fixture}/gradle-ro/modules-2/gc.properties"
bad_cache_metadata_artifact="${scratch_dir}/bad-cache-metadata.tar.zst"
tar_zstd_create "$bad_cache_metadata_artifact" "$bad_cache_metadata_fixture"
bad_cache_metadata_sha="$(compute_sha256 "$bad_cache_metadata_artifact")"
bad_cache_metadata_root="${scratch_dir}/bad-cache-metadata"
mkdir -p "$bad_cache_metadata_root/home"
if run_action "$bad_cache_metadata_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${bad_cache_metadata_root}/opt/kast" \
  "INPUT_GRADLE-RO-CACHE-URL=file://${bad_cache_metadata_artifact}" \
  "INPUT_GRADLE-RO-CACHE-SHA256=${bad_cache_metadata_sha}" \
  INPUT_FAIL-ON-CACHE-MISS=true \
  >"${bad_cache_metadata_root}/out" 2>"${bad_cache_metadata_root}/err"; then
  die "strict bad cache metadata unexpectedly succeeded"
fi
require_contains "${bad_cache_metadata_root}/err" "mutable metadata" "bad cache metadata failure"

doctor_fixture="${scratch_dir}/doctor-fixture"
write_runtime_fixture "$doctor_fixture" failing-doctor
doctor_artifact="${scratch_dir}/kast-headless-linux-x64-doctor.tar.zst"
tar_zstd_create "$doctor_artifact" "${doctor_fixture}/runtime"
doctor_sha="$(compute_sha256 "$doctor_artifact")"
doctor_manifest="${scratch_dir}/kast-runtime-manifest-doctor.json"
write_manifest "$doctor_manifest" "$doctor_sha"
doctor_strict_root="${scratch_dir}/doctor-strict"
mkdir -p "$doctor_strict_root/home"
if run_action "$doctor_strict_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${doctor_artifact}" \
  "INPUT_ARTIFACT-SHA256=${doctor_sha}" \
  "INPUT_MANIFEST-URL=file://${doctor_manifest}" \
  "INPUT_INSTALL-DIR=${doctor_strict_root}/opt/kast" \
  INPUT_STRICT=true \
  >"${doctor_strict_root}/out" 2>"${doctor_strict_root}/err"; then
  die "strict failing doctor unexpectedly succeeded"
fi
require_contains "${doctor_strict_root}/err" "doctor failed" "strict doctor stderr"

doctor_rollback_root="${scratch_dir}/doctor-rollback"
mkdir -p "$doctor_rollback_root/home"
run_action "$doctor_rollback_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${doctor_rollback_root}/opt/kast" \
  >"${doctor_rollback_root}/good-out" 2>"${doctor_rollback_root}/good-err"
rollback_target_before="$(readlink "${doctor_rollback_root}/opt/kast/current")"
doctor_rollback_manifest="${scratch_dir}/kast-runtime-manifest-doctor-rollback.json"
write_manifest "$doctor_rollback_manifest" "$doctor_sha" 1 9.8.8
if run_action "$doctor_rollback_root" \
  INPUT_VERSION=9.8.8 \
  "INPUT_ARTIFACT-URL=file://${doctor_artifact}" \
  "INPUT_ARTIFACT-SHA256=${doctor_sha}" \
  "INPUT_MANIFEST-URL=file://${doctor_rollback_manifest}" \
  "INPUT_INSTALL-DIR=${doctor_rollback_root}/opt/kast" \
  INPUT_STRICT=true \
  >"${doctor_rollback_root}/bad-out" 2>"${doctor_rollback_root}/bad-err"; then
  die "strict failing doctor reinstall unexpectedly succeeded"
fi
require_contains "${doctor_rollback_root}/bad-err" "doctor failed" "strict reinstall doctor stderr"
[[ "$(readlink "${doctor_rollback_root}/opt/kast/current")" == "$rollback_target_before" ]] \
  || die "strict failing reinstall did not restore the previous current symlink"
require_contains "${doctor_rollback_root}/home/.config/kast/config.toml" 'version = "9.8.7"' "strict reinstall rollback config"
"${doctor_rollback_root}/opt/kast/current/bin/kast" doctor >/dev/null \
  || die "strict failing reinstall left current pointing at the failing runtime"

doctor_same_version_rollback_root="${scratch_dir}/doctor-same-version-rollback"
mkdir -p "$doctor_same_version_rollback_root/home"
run_action "$doctor_same_version_rollback_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${runtime_artifact}" \
  "INPUT_ARTIFACT-SHA256=${runtime_sha}" \
  "INPUT_MANIFEST-URL=file://${runtime_manifest}" \
  "INPUT_INSTALL-DIR=${doctor_same_version_rollback_root}/opt/kast" \
  >"${doctor_same_version_rollback_root}/good-out" 2>"${doctor_same_version_rollback_root}/good-err"
same_version_target_before="$(readlink "${doctor_same_version_rollback_root}/opt/kast/current")"
doctor_same_version_manifest="${scratch_dir}/kast-runtime-manifest-doctor-same-version.json"
write_manifest "$doctor_same_version_manifest" "$doctor_sha" 1 9.8.7
if run_action "$doctor_same_version_rollback_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${doctor_artifact}" \
  "INPUT_ARTIFACT-SHA256=${doctor_sha}" \
  "INPUT_MANIFEST-URL=file://${doctor_same_version_manifest}" \
  "INPUT_INSTALL-DIR=${doctor_same_version_rollback_root}/opt/kast" \
  INPUT_STRICT=true \
  >"${doctor_same_version_rollback_root}/bad-out" 2>"${doctor_same_version_rollback_root}/bad-err"; then
  die "strict failing same-version reinstall unexpectedly succeeded"
fi
require_contains "${doctor_same_version_rollback_root}/bad-err" "doctor failed" "strict same-version reinstall doctor stderr"
[[ "$(readlink "${doctor_same_version_rollback_root}/opt/kast/current")" == "$same_version_target_before" ]] \
  || die "strict failing same-version reinstall changed the current symlink"
require_contains "${doctor_same_version_rollback_root}/home/.config/kast/config.toml" 'version = "9.8.7"' "strict same-version reinstall rollback config"
"${doctor_same_version_rollback_root}/opt/kast/current/bin/kast" doctor >/dev/null \
  || die "strict failing same-version reinstall replaced the working runtime"
if find "${doctor_same_version_rollback_root}/opt/kast" -maxdepth 1 -name '.setup-kast-*' | grep -q .; then
  die "strict failing same-version reinstall left a staging install directory"
fi

doctor_root="${scratch_dir}/doctor-nonstrict"
mkdir -p "$doctor_root/home"
run_action "$doctor_root" \
  INPUT_VERSION=9.8.7 \
  "INPUT_ARTIFACT-URL=file://${doctor_artifact}" \
  "INPUT_ARTIFACT-SHA256=${doctor_sha}" \
  "INPUT_MANIFEST-URL=file://${doctor_manifest}" \
  "INPUT_INSTALL-DIR=${doctor_root}/opt/kast" \
  INPUT_STRICT=false \
  >"${doctor_root}/out" 2>"${doctor_root}/err"
require_contains "${doctor_root}/err" "kast doctor failed" "non-strict doctor warning"

printf '%s\n' "setup-kast action contract passed"
