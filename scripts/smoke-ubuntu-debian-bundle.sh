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
        info.external_attr = (mode & 0o777) << 16
        archive.writestr(info, path.read_bytes())
PY
}

link_external_tool() {
  local tool_name="$1"
  local target_dir="$2"
  local tool_path
  tool_path="$(type -P "$tool_name" || true)"
  [[ -n "$tool_path" ]] || die "Missing external tool for no-python installer smoke: $tool_name"
  ln -sf "$tool_path" "${target_dir}/${tool_name}"
}

prepare_no_python_path() {
  local target_dir="$1"
  mkdir -p "$target_dir"
  local tool_name
  for tool_name in bash sh tar mkdir mktemp rm cp basename dirname pwd uname awk; do
    link_external_tool "$tool_name" "$target_dir"
  done
  if type -P sha256sum >/dev/null 2>&1; then
    link_external_tool sha256sum "$target_dir"
  elif type -P shasum >/dev/null 2>&1; then
    link_external_tool shasum "$target_dir"
  else
    die "Neither sha256sum nor shasum is available"
  fi
  [[ ! -e "${target_dir}/python3" ]] || die "No-python installer smoke PATH unexpectedly contains python3"
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

need_tool git
need_tool cargo
need_tool python3
need_tool tar

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ubuntu-debian-bundle-smoke.XXXXXX")"
scratch_dir="$(cd -- "$scratch_dir" && pwd -P)"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

version="v9.8.7"
bundle_kind="headless"
platform="ubuntu-debian-headless-x86_64"
backend_archive_root="backend-headless"
backend_install_name="headless-${version}"
backend_launcher="kast-headless"
backend_role="headless-backend"
artifact_dir="${scratch_dir}/artifacts"
cli_tree="${scratch_dir}/cli"
backend_tree="${scratch_dir}/backend"
extract_dir="${scratch_dir}/extract"
home_dir="${scratch_dir}/home"
config_home="${scratch_dir}/config"
install_root="${scratch_dir}/ubuntu-debian-root"
bin_dir="${scratch_dir}/bin"

mkdir -p \
  "$artifact_dir" \
  "$cli_tree" \
  "${backend_tree}/${backend_archive_root}/runtime-libs" \
  "$extract_dir" \
  "$home_dir" \
  "$config_home" \
  "$install_root" \
  "$bin_dir"

mkdir -p \
  "${backend_tree}/${backend_archive_root}/idea-home/lib" \
  "${backend_tree}/${backend_archive_root}/idea-home/modules" \
  "${backend_tree}/${backend_archive_root}/idea-home/plugins/kast-headless/lib"

if [[ -n "${KAST_UBUNTU_DEBIAN_CLI_BIN:-}" ]]; then
  cli_bin="$KAST_UBUNTU_DEBIAN_CLI_BIN"
else
  cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --locked
  cli_bin="${repo_root}/cli-rs/target/debug/kast"
fi
[[ -x "$cli_bin" ]] || die "Rust CLI binary was not built at ${cli_bin}"
packager_command=("$cli_bin" package ubuntu-debian-bundle --repo-root "$repo_root")
cp "$cli_bin" "${cli_tree}/kast"
chmod +x "${cli_tree}/kast"

cat > "${backend_tree}/${backend_archive_root}/${backend_launcher}" <<'FAKE_BACKEND'
#!/usr/bin/env bash
printf '%s\n' "fake backend"
FAKE_BACKEND
chmod +x "${backend_tree}/${backend_archive_root}/${backend_launcher}"
printf '%s\n' "fake.jar" > "${backend_tree}/${backend_archive_root}/runtime-libs/classpath.txt"
printf '%s\n' "fake runtime lib" > "${backend_tree}/${backend_archive_root}/runtime-libs/fake.jar"
printf '%s\n' "fake nio fs" > "${backend_tree}/${backend_archive_root}/idea-home/lib/nio-fs.jar"
printf '%s\n' "fake module descriptors" > "${backend_tree}/${backend_archive_root}/idea-home/modules/module-descriptors.dat"
printf '%s\n' "fake plugin lib" > "${backend_tree}/${backend_archive_root}/idea-home/plugins/kast-headless/lib/backend-headless.jar"
mkdir -p "${backend_tree}/${backend_archive_root}/lib"
printf '%s\n' "fat jar placeholder" > "${backend_tree}/${backend_archive_root}/lib/${backend_archive_root}-9.8.7-all.jar"

cli_zip="${artifact_dir}/kast-${version}-linux-x64.zip"
backend_zip="${artifact_dir}/${backend_archive_root}-${version}.zip"
bundle_path="${artifact_dir}/kast-${platform}-${version}.tar.gz"
zip_dir "$cli_zip" "$cli_tree"
zip_dir "$backend_zip" "$backend_tree"

malicious_cli_zip="${artifact_dir}/malicious-cli.zip"
python3 - "$malicious_cli_zip" <<'PY'
import sys
import zipfile
from pathlib import Path

with zipfile.ZipFile(Path(sys.argv[1]), "w", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("../outside", "escape")
PY

expect_failure_contains \
  "unsafe zip member" \
  "${packager_command[@]}" \
  --cli-archive "$malicious_cli_zip" \
  --backend-archive "$backend_zip" \
  --version "$version" \
  --bundle-output "${artifact_dir}/must-not-exist.tar.gz"

"${packager_command[@]}" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_zip" \
  --version "$version" \
  --bundle-output "$bundle_path"

[[ -f "$bundle_path" ]] || die "Bundle tarball was not created: $bundle_path"
[[ -f "${bundle_path}.sha256" ]] || die "Bundle SHA-256 sidecar was not created"
grep -Fq "$(basename -- "$bundle_path")" "${bundle_path}.sha256" || die "SHA-256 sidecar does not name the bundle"

tar -xzf "$bundle_path" -C "$extract_dir"
extracted_bundle_root="${extract_dir}/kast-${platform}-${version}"
bundle_root="${extract_dir}/renamed-kast-bundle"
mv "$extracted_bundle_root" "$bundle_root"

[[ -x "${bundle_root}/bin/kast" ]] || die "Bundle missing executable Rust CLI"
[[ -x "${bundle_root}/lib/backends/${backend_install_name}/${backend_launcher}" ]] || die "Bundle missing ${backend_launcher} launcher"
[[ -f "${bundle_root}/lib/backends/${backend_install_name}/runtime-libs/classpath.txt" ]] || die "Bundle missing runtime classpath"
[[ -f "${bundle_root}/lib/backends/${backend_install_name}/idea-home/lib/nio-fs.jar" ]] || die "Bundle missing headless IDEA home"
[[ -f "${bundle_root}/lib/backends/${backend_install_name}/idea-home/modules/module-descriptors.dat" ]] || die "Bundle missing headless module descriptors"
[[ -d "${bundle_root}/lib/backends/${backend_install_name}/idea-home/plugins/kast-headless" ]] || die "Bundle missing bundled kast-headless plugin"
[[ -x "${bundle_root}/scripts/install-ubuntu-debian.sh" ]] || die "Bundle missing canonical installer"
[[ ! -e "${bundle_root}/scripts/install-kast-devin.sh" ]] || die "Bundle must not include Devin-specific installer"
[[ ! -e "${bundle_root}/scripts/verify-kast-devin.sh" ]] || die "Bundle must not include a second verifier script"
[[ -f "${bundle_root}/manifest.json" ]] || die "Bundle missing manifest"
[[ -f "${bundle_root}/LICENSE" ]] || die "Bundle missing license"
[[ ! -e "${bundle_root}/backend-idea" ]] || die "Bundle must not include the IDEA plugin"
[[ ! -e "${bundle_root}/java" ]] || die "Bundle must not include Java"

python3 - "${bundle_root}/manifest.json" "$version" "$platform" "$bundle_kind" "$backend_role" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
version = sys.argv[2]
assert payload["schemaVersion"] == 2, payload
assert payload["kind"] == "KAST_INSTALL_BUNDLE", payload
assert payload["profile"] == "ubuntu-debian-headless", payload
assert payload["version"] == version, payload
assert payload["platform"] == sys.argv[3], payload
assert payload["entrypoint"] == "scripts/install-ubuntu-debian.sh", payload
activation = payload["activation"]
assert activation["cli"]["path"] == "bin/kast", payload
assert activation["backend"]["kind"] == sys.argv[4], payload
assert activation["backend"]["name"] == "headless", payload
assert activation["backend"]["installDir"] == f"lib/backends/headless-{version}", payload
assert activation["backend"]["runtimeLibsDir"] == "runtime-libs", payload
assert activation["backend"]["ideaHome"] == "idea-home", payload
assert activation["backend"]["requiredPlugin"] == "idea-home/plugins/kast-headless", payload
assert "-Didea.force.use.core.classloader=true" in activation["shim"]["javaOpts"], payload
assert activation["shim"]["exportsInstallRoot"] is True, payload
assert activation["shim"]["exportsConfigHome"] is True, payload
roles = {entry["role"] for entry in payload["artifacts"]}
assert {"cli", sys.argv[5]}.issubset(roles), payload
PY

manifest_install_root="${scratch_dir}/manifest-install-root"
manifest_bin_dir="${scratch_dir}/manifest-bin"
manifest_config_home="${scratch_dir}/manifest-config"
mkdir -p "$manifest_bin_dir"

HOME="$home_dir" \
PATH="$manifest_bin_dir:$PATH" \
KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
KAST_UBUNTU_DEBIAN_ROOT="$manifest_install_root" \
KAST_UBUNTU_DEBIAN_BIN_DIR="$manifest_bin_dir" \
KAST_UBUNTU_DEBIAN_CONFIG_HOME="$manifest_config_home" \
KAST_JAVA_CMD=sh \
"${bundle_root}/scripts/install-ubuntu-debian.sh" install

manifest_config_file="${manifest_config_home}/config.toml"
manifest_manifest_file="${manifest_install_root}/install.json"
manifest_installed_home="${manifest_install_root}/versions/${version}"
[[ -f "$manifest_config_file" ]] || die "Manifest-based install did not write config.toml"
[[ -f "$manifest_manifest_file" ]] || die "Manifest-based install did not write install.json"
[[ -L "${manifest_install_root}/current" ]] || die "Manifest-based install did not activate current"
grep -Fq 'defaultBackend = "headless"' "$manifest_config_file" \
  || die "Manifest-based config.toml does not set behavior backend"
if grep -Eq '^(installRoot|binDir|libDir|cacheDir|logsDir|runtimeDir|descriptorDir|socketDir|runtimeLibsDir|ideaHome|binaryPath) = ' "$manifest_config_file"; then
  die "Manifest-based config.toml must not write install-owned paths"
fi
python3 - "$manifest_manifest_file" "$version" "$manifest_install_root" "$manifest_installed_home" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
version = sys.argv[2]
install_root = sys.argv[3]
install_home = sys.argv[4]
assert payload["tool"] == "kast", payload
assert payload["activeVersion"] == version, payload
assert payload["profile"] == "ubuntu-debian-headless", payload
assert payload["roots"]["install"] == install_root, payload
assert payload["entrypoints"]["activeBinary"] == f"{install_home}/bin/kast", payload
backend = payload["backends"][0]
assert backend["name"] == "headless", payload
assert backend["runtimeLibsDir"] == f"{install_home}/lib/backends/headless/current/runtime-libs", payload
assert backend["ideaHome"] == f"{install_home}/lib/backends/headless/current/idea-home", payload
PY

HOME="$home_dir" \
PATH="$bin_dir:$PATH" \
KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="$bundle_path" \
KAST_UBUNTU_DEBIAN_ROOT="$install_root" \
KAST_UBUNTU_DEBIAN_BIN_DIR="$bin_dir" \
KAST_UBUNTU_DEBIAN_CONFIG_HOME="$config_home" \
KAST_JAVA_CMD=sh \
"${repo_root}/scripts/install-ubuntu-debian.sh" install

installed_home="${install_root}/versions/${version}"
installed_kast="${bin_dir}/kast"
config_file="${config_home}/config.toml"
install_manifest="${install_root}/install.json"

[[ -x "$installed_kast" ]] || die "Installed kast is not executable"
[[ -f "$installed_kast" && ! -L "$installed_kast" ]] || die "Installed headless kast must be an executable shim"
grep -Fq -- "-Didea.force.use.core.classloader=true" "$installed_kast" \
  || die "Installed headless kast shim must export the core classloader JVM option"
grep -Fq -- "KAST_INSTALL_ROOT" "$installed_kast" \
  || die "Installed headless kast shim must export KAST_INSTALL_ROOT"
[[ -L "${install_root}/current" ]] || die "Installer did not activate current"
[[ -f "$install_manifest" ]] || die "Installer did not write install.json"
[[ -f "$config_file" ]] || die "Installer did not write config.toml"
grep -Fq 'defaultBackend = "headless"' "$config_file" || die "config.toml does not default to headless runtime"
grep -Fq "[backends.headless]" "$config_file" || die "config.toml does not include headless backend config"
if grep -Eq '^(installRoot|binDir|libDir|cacheDir|logsDir|runtimeDir|descriptorDir|socketDir|runtimeLibsDir|ideaHome|binaryPath) = ' "$config_file"; then
  die "config.toml must not write install-owned paths"
fi
python3 - "$install_manifest" "$version" "$install_root" "$installed_home" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
version = sys.argv[2]
install_root = sys.argv[3]
install_home = sys.argv[4]
assert payload["activeVersion"] == version, payload
assert payload["version"] == version.removeprefix("v"), payload
assert payload["backendVersion"] == version.removeprefix("v"), payload
assert payload["roots"]["install"] == install_root, payload
assert payload["entrypoints"]["activeBinary"] == f"{install_home}/bin/kast", payload
backend = payload["backends"][0]
assert backend["runtimeLibsDir"] == f"{install_home}/lib/backends/headless/current/runtime-libs", payload
assert backend["ideaHome"] == f"{install_home}/lib/backends/headless/current/idea-home", payload
PY

HOME="$home_dir" \
PATH="$bin_dir:$PATH" \
KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
KAST_UBUNTU_DEBIAN_VERSION="$version" \
KAST_UBUNTU_DEBIAN_BASE_URL="file://${scratch_dir}/missing-release" \
KAST_UBUNTU_DEBIAN_ROOT="$install_root" \
KAST_UBUNTU_DEBIAN_BIN_DIR="$bin_dir" \
KAST_UBUNTU_DEBIAN_CONFIG_HOME="$config_home" \
KAST_JAVA_CMD=sh \
"${repo_root}/scripts/install-ubuntu-debian.sh" install

expect_failure_contains \
  "activeVersion" \
  env \
  HOME="$home_dir" \
  PATH="$bin_dir:$PATH" \
  KAST_UBUNTU_DEBIAN_VERSION="v0.0.1" \
  KAST_UBUNTU_DEBIAN_ROOT="$install_root" \
  KAST_UBUNTU_DEBIAN_BIN_DIR="$bin_dir" \
  KAST_UBUNTU_DEBIAN_CONFIG_HOME="$config_home" \
  KAST_JAVA_CMD=sh \
  "${repo_root}/scripts/install-ubuntu-debian.sh" verify

no_python_tools="${scratch_dir}/no-python-tools"
no_python_install_root="${scratch_dir}/no-python-install-root"
no_python_bin_dir="${scratch_dir}/no-python-bin"
no_python_config_home="${scratch_dir}/no-python-config"
prepare_no_python_path "$no_python_tools"
mkdir -p "$no_python_bin_dir"

HOME="$home_dir" \
PATH="${no_python_tools}:${no_python_bin_dir}" \
KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="$bundle_path" \
KAST_UBUNTU_DEBIAN_ROOT="$no_python_install_root" \
KAST_UBUNTU_DEBIAN_BIN_DIR="$no_python_bin_dir" \
KAST_UBUNTU_DEBIAN_CONFIG_HOME="$no_python_config_home" \
KAST_JAVA_CMD=sh \
"${repo_root}/scripts/install-ubuntu-debian.sh" install

HOME="$home_dir" \
PATH="${no_python_tools}:${no_python_bin_dir}" \
KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="$bundle_path" \
KAST_UBUNTU_DEBIAN_ROOT="$no_python_install_root" \
KAST_UBUNTU_DEBIAN_BIN_DIR="$no_python_bin_dir" \
KAST_UBUNTU_DEBIAN_CONFIG_HOME="$no_python_config_home" \
KAST_JAVA_CMD=sh \
"${repo_root}/scripts/install-ubuntu-debian.sh" verify

bundle_without_sidecar="${artifact_dir}/kast-${platform}-v9.8.6.tar.gz"
cp "$bundle_path" "$bundle_without_sidecar"
expect_failure_contains \
  "Missing SHA-256 sidecar" \
  env \
  HOME="$home_dir" \
  PATH="$bin_dir:$PATH" \
  KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
  KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="$bundle_without_sidecar" \
  KAST_UBUNTU_DEBIAN_ROOT="$install_root" \
  KAST_UBUNTU_DEBIAN_BIN_DIR="$bin_dir" \
  KAST_UBUNTU_DEBIAN_CONFIG_HOME="$config_home" \
  KAST_JAVA_CMD=sh \
  "${repo_root}/scripts/install-ubuntu-debian.sh" install

HOME="$home_dir" \
PATH="$bin_dir:$PATH" \
KAST_UBUNTU_DEBIAN_VERSION="$version" \
KAST_UBUNTU_DEBIAN_ROOT="$install_root" \
KAST_UBUNTU_DEBIAN_BIN_DIR="$bin_dir" \
KAST_UBUNTU_DEBIAN_CONFIG_HOME="$config_home" \
KAST_JAVA_CMD=sh \
"${repo_root}/scripts/install-ubuntu-debian.sh" verify

expected_digest="$(compute_sha256 "$bundle_path")"
actual_digest="$(awk '{ print $1 }' "${bundle_path}.sha256")"
[[ "$actual_digest" == "$expected_digest" ]] || die "SHA-256 sidecar digest mismatch"

printf '%s\n' "Ubuntu/Debian bundle smoke test passed"
