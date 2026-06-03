#!/usr/bin/env bash
set -Eeuo pipefail

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

extract_zip_archive() {
  local archive_path="$1"
  local output_dir="$2"

  python3 - "$archive_path" "$output_dir" <<'PY'
import sys
import zipfile
from pathlib import Path

archive_path = Path(sys.argv[1])
output_dir = Path(sys.argv[2])
output_dir.mkdir(parents=True, exist_ok=True)
resolved_output = output_dir.resolve()

with zipfile.ZipFile(archive_path) as archive:
    for member in archive.namelist():
        destination = (output_dir / member).resolve()
        if destination != resolved_output and not str(destination).startswith(str(resolved_output) + "/"):
            raise SystemExit(f"unsafe zip member: {member}")
    archive.extractall(output_dir)
PY
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/package-devin-headless-runtime.sh --cli-archive <zip> --backend-archive <zip> --version <tag> [--output <tar.gz>]

Build a Linux x64 Kast Devin headless runtime bundle from a Rust CLI archive and
an agent-profile backend-headless portable archive.
USAGE
}

repo_root="$(resolve_repo_root)"
cli_archive=""
backend_archive=""
version=""
output_path=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cli-archive)
      [[ $# -ge 2 ]] || die "Missing value for --cli-archive"
      cli_archive="$2"; shift 2 ;;
    --cli-archive=*)
      cli_archive="${1#--cli-archive=}"; shift ;;
    --backend-archive)
      [[ $# -ge 2 ]] || die "Missing value for --backend-archive"
      backend_archive="$2"; shift 2 ;;
    --backend-archive=*)
      backend_archive="${1#--backend-archive=}"; shift ;;
    --version)
      [[ $# -ge 2 ]] || die "Missing value for --version"
      version="$2"; shift 2 ;;
    --version=*)
      version="${1#--version=}"; shift ;;
    --output)
      [[ $# -ge 2 ]] || die "Missing value for --output"
      output_path="$2"; shift 2 ;;
    --output=*)
      output_path="${1#--output=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$cli_archive" ]] || { usage; die "--cli-archive is required"; }
[[ -n "$backend_archive" ]] || { usage; die "--backend-archive is required"; }
[[ -n "$version" ]] || { usage; die "--version is required"; }
[[ -f "$cli_archive" ]] || die "CLI archive not found: $cli_archive"
[[ -f "$backend_archive" ]] || die "Backend archive not found: $backend_archive"
[[ -x "${repo_root}/scripts/verify-kast-devin-runtime.sh" ]] || die "Missing scripts/verify-kast-devin-runtime.sh"

need_tool python3
need_tool tar

platform="devin-headless-linux-x64"
bundle_name="kast-devin-headless-runtime-linux-x64-${version}"
backend_install_name="headless-${version}"
if [[ -z "$output_path" ]]; then
  output_path="${repo_root}/dist/${bundle_name}.tar.gz"
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-headless-package.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

cli_extract="${tmp_dir}/cli"
backend_extract="${tmp_dir}/backend"
staging_root="${tmp_dir}/${bundle_name}"
mkdir -p "$cli_extract" "$backend_extract" "$staging_root/bin" \
  "${staging_root}/lib/backends" \
  "${staging_root}/scripts" \
  "$(dirname -- "$output_path")"

extract_zip_archive "$cli_archive" "$cli_extract"
extract_zip_archive "$backend_archive" "$backend_extract"

cli_bin="${cli_extract}/kast"
backend_root="${backend_extract}/backend-headless"
[[ -f "$cli_bin" ]] || die "CLI archive must contain kast at its root"
[[ -d "$backend_root" ]] || die "Backend archive must contain backend-headless/"
[[ -x "${backend_root}/kast-headless" || -f "${backend_root}/kast-headless" ]] || die "Backend archive missing kast-headless launcher"
[[ -f "${backend_root}/runtime-libs/classpath.txt" ]] || die "Backend archive missing runtime-libs/classpath.txt"
[[ -f "${backend_root}/idea-home/lib/nio-fs.jar" ]] || die "Backend archive missing idea-home/lib/nio-fs.jar"
[[ -f "${backend_root}/idea-home/modules/module-descriptors.dat" ]] || die "Backend archive missing idea-home/modules/module-descriptors.dat"
[[ -d "${backend_root}/idea-home/plugins/kast-headless" ]] || die "Backend archive missing bundled kast-headless plugin"

if [[ -d "${backend_root}/libs" ]] && find "${backend_root}/libs" -name '*-all.jar' -print -quit | grep -q .; then
  find "${backend_root}/libs" -name '*-all.jar' -print >&2
  die "Devin headless backend archive must not contain fat jars"
fi

cp "$cli_bin" "${staging_root}/bin/kast"
chmod 755 "${staging_root}/bin/kast"
cp -R "$backend_root" "${staging_root}/lib/backends/${backend_install_name}"
chmod 755 "${staging_root}/lib/backends/${backend_install_name}/kast-headless"
cp "${repo_root}/scripts/verify-kast-devin-runtime.sh" "${staging_root}/scripts/verify-kast-devin-runtime.sh"
chmod 755 "${staging_root}/scripts/verify-kast-devin-runtime.sh"

setup_script="${staging_root}/scripts/setup-kast-devin-runtime.sh"
cat > "$setup_script" <<'SETUP'
#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

toml_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s\n' "$value"
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/setup-kast-devin-runtime.sh [--prefix <bundle-root>]

Generate config.toml for the actual unpacked Kast Devin runtime prefix.
USAGE
}

resolve_default_prefix() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
  cd -- "${script_dir}/.." >/dev/null 2>&1 && pwd
}

prefix=""
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

version="__KAST_DEVIN_VERSION__"
normalized_version="${version#v}"
backend_install_name="__KAST_DEVIN_BACKEND_INSTALL_NAME__"
platform="devin-headless-linux-x64"
backend_root="${prefix}/lib/backends/${backend_install_name}"
runtime_libs="${backend_root}/runtime-libs"
idea_home="${backend_root}/idea-home"
bin_path="${prefix}/bin/kast"
config_file="${prefix}/config.toml"

[[ -x "$bin_path" ]] || die "Missing executable CLI: $bin_path"
[[ -x "${backend_root}/kast-headless" ]] || die "Missing executable headless launcher: ${backend_root}/kast-headless"
[[ -f "${runtime_libs}/classpath.txt" ]] || die "Missing runtime classpath: ${runtime_libs}/classpath.txt"
[[ -f "${idea_home}/lib/nio-fs.jar" ]] || die "Missing IDEA home: ${idea_home}/lib/nio-fs.jar"
[[ -f "${idea_home}/modules/module-descriptors.dat" ]] || die "Missing IDEA module descriptors"

mkdir -p "${prefix}/cache/daemons" "${prefix}/logs"
cat > "$config_file" <<TOML
[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[runtime]
defaultBackend = "headless"

[paths]
installRoot = "$(toml_escape "$prefix")"
binDir = "$(toml_escape "${prefix}/bin")"
libDir = "$(toml_escape "${prefix}/lib")"
cacheDir = "$(toml_escape "${prefix}/cache")"
logsDir = "$(toml_escape "${prefix}/logs")"
descriptorDir = "$(toml_escape "${prefix}/cache/daemons")"
socketDir = "$(toml_escape "${TMPDIR:-/tmp}")"

[backends.headless]
runtimeLibsDir = "$(toml_escape "$runtime_libs")"
ideaHome = "$(toml_escape "$idea_home")"

[cli]
binaryPath = "$(toml_escape "$bin_path")"

[install]
version = "$(toml_escape "$normalized_version")"
backendVersion = "$(toml_escape "$normalized_version")"
installedAt = "$(toml_escape "$platform"):${version}"
platform = "$(toml_escape "$platform")"
components = ["cli", "headless-backend", "config"]
managedPaths = ["bin", "lib", "cache", "logs", "config.toml"]
shellRcPatches = []
repos = []
schemaVersion = 6

[[install.backends]]
name = "headless"
version = "$(toml_escape "$normalized_version")"
installDir = "$(toml_escape "$backend_root")"
runtimeLibsDir = "$(toml_escape "$runtime_libs")"
ideaHome = "$(toml_escape "$idea_home")"
TOML

printf '%s\n' "Wrote ${config_file}"
SETUP

python3 - "$setup_script" "$version" "$backend_install_name" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
content = path.read_text(encoding="utf-8")
content = content.replace("__KAST_DEVIN_VERSION__", sys.argv[2])
content = content.replace("__KAST_DEVIN_BACKEND_INSTALL_NAME__", sys.argv[3])
path.write_text(content, encoding="utf-8")
PY
chmod 755 "$setup_script"

cat > "${staging_root}/SETUP.md" <<'SETUP'
# Kast Devin Headless Runtime

Unpack this archive into the snapshot location, then run:

```bash
scripts/setup-kast-devin-runtime.sh --prefix "$PWD"
scripts/verify-kast-devin-runtime.sh --prefix "$PWD"
```

The setup script writes `config.toml` with absolute paths for the unpacked
prefix. Run Kast commands with `KAST_CONFIG_HOME` pointing at this directory.
SETUP

if [[ -f "${repo_root}/LICENSE" ]]; then
  cp "${repo_root}/LICENSE" "${staging_root}/LICENSE"
fi

cli_sha="$(compute_sha256 "$cli_archive")"
backend_sha="$(compute_sha256 "$backend_archive")"
build_commit="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf 'unknown')"

python3 - "${staging_root}/manifest.json" "$version" "$platform" "$backend_install_name" "$cli_sha" "$backend_sha" "$build_commit" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
version = sys.argv[2]
platform = sys.argv[3]
backend_install_name = sys.argv[4]
cli_sha = sys.argv[5]
backend_sha = sys.argv[6]
build_commit = sys.argv[7]
payload = {
    "schemaVersion": 1,
    "kind": "KAST_DEVIN_HEADLESS_RUNTIME",
    "version": version,
    "platform": platform,
    "backendInstallName": backend_install_name,
    "config": {
        "generatedBy": "scripts/setup-kast-devin-runtime.sh",
        "path": "config.toml",
    },
    "buildCommit": build_commit,
    "artifacts": [
        {
            "role": "cli",
            "path": "bin/kast",
            "sourceSha256": cli_sha,
        },
        {
            "role": "headless-backend",
            "path": f"lib/backends/{backend_install_name}",
            "sourceSha256": backend_sha,
        },
    ],
}
manifest_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

rm -f "$output_path" "${output_path}.sha256"
COPYFILE_DISABLE=1 tar --no-xattrs -C "$tmp_dir" -czf "$output_path" "$bundle_name"
printf '%s  %s\n' "$(compute_sha256 "$output_path")" "$(basename -- "$output_path")" > "${output_path}.sha256"
log "Wrote ${output_path}"
