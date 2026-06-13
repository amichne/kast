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
Usage: scripts/package-ubuntu-debian-bundle.sh --cli-archive <zip> --backend-archive <zip> --version <tag> [--output <tar.gz>]

Build the Ubuntu/Debian x86_64 Kast bundle from the Rust CLI archive and the
headless backend portable archive.
USAGE
}

repo_root="$(resolve_repo_root)"
cli_archive=""
backend_archive=""
version="${KAST_UBUNTU_DEBIAN_VERSION:-}"
output_path=""
bundle_kind="headless"

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
[[ -x "${repo_root}/kast.sh" ]] || die "Missing kast.sh"
[[ -x "${repo_root}/scripts/install-ubuntu-debian.sh" ]] || die "Missing scripts/install-ubuntu-debian.sh"

need_tool python3
need_tool tar

platform="ubuntu-debian-headless-x86_64"
backend_archive_root="backend-headless"
backend_install_name="headless-${version}"
backend_launcher="kast-headless"
backend_role="headless-backend"

bundle_name="kast-${platform}-${version}"
if [[ -z "$output_path" ]]; then
  output_path="${repo_root}/dist/${bundle_name}.tar.gz"
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ubuntu-debian-package.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

cli_extract="${tmp_dir}/cli"
backend_extract="${tmp_dir}/backend"
staging_root="${tmp_dir}/${bundle_name}"
mkdir -p "$cli_extract" "$backend_extract" "$staging_root/bin" \
  "${staging_root}/lib/backends" \
  "$staging_root/scripts" \
  "$(dirname -- "$output_path")"

extract_zip_archive "$cli_archive" "$cli_extract"
extract_zip_archive "$backend_archive" "$backend_extract"

cli_bin="${cli_extract}/kast"
[[ -f "$cli_bin" ]] || die "CLI archive must contain kast at its root"
backend_root="${backend_extract}/${backend_archive_root}"
[[ -d "$backend_root" ]] || die "Backend archive must contain ${backend_archive_root}/"
[[ -f "${backend_root}/runtime-libs/classpath.txt" ]] || die "Backend archive missing runtime-libs/classpath.txt"
[[ -f "${backend_root}/${backend_launcher}" ]] || die "Backend archive missing ${backend_launcher} launcher"
[[ -f "${backend_root}/idea-home/lib/nio-fs.jar" ]] || die "Backend archive missing headless idea-home/lib/nio-fs.jar"
[[ -f "${backend_root}/idea-home/modules/module-descriptors.dat" ]] || die "Backend archive missing headless idea-home/modules/module-descriptors.dat"
[[ -d "${backend_root}/idea-home/plugins/kast-headless" ]] || die "Backend archive missing bundled kast-headless plugin"

cp "$cli_bin" "${staging_root}/bin/kast"
chmod 755 "${staging_root}/bin/kast"
mv "$backend_root" "${staging_root}/lib/backends/${backend_install_name}"
chmod 755 "${staging_root}/lib/backends/${backend_install_name}/${backend_launcher}"
cp "${repo_root}/kast.sh" "${staging_root}/kast.sh"
chmod 755 "${staging_root}/kast.sh"
cp "${repo_root}/scripts/install-ubuntu-debian.sh" "${staging_root}/scripts/install-ubuntu-debian.sh"
chmod 755 "${staging_root}/scripts/install-ubuntu-debian.sh"
if [[ -f "${repo_root}/LICENSE" ]]; then
  cp "${repo_root}/LICENSE" "${staging_root}/LICENSE"
else
  cat > "${staging_root}/LICENSE" <<'LICENSE'
Kast distribution notice

SPDX-License-Identifier: Apache-2.0
License text: https://www.apache.org/licenses/LICENSE-2.0
LICENSE
fi

cli_sha="$(compute_sha256 "$cli_archive")"
backend_sha="$(compute_sha256 "$backend_archive")"
build_commit="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf 'unknown')"

python3 - "${staging_root}/manifest.json" "$version" "$platform" "$bundle_kind" "$backend_role" "$backend_install_name" "$cli_sha" "$backend_sha" "$build_commit" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
version = sys.argv[2]
platform = sys.argv[3]
bundle_kind = sys.argv[4]
backend_role = sys.argv[5]
backend_install_name = sys.argv[6]
cli_sha = sys.argv[7]
backend_sha = sys.argv[8]
build_commit = sys.argv[9]
payload = {
    "schemaVersion": 1,
    "kind": "KAST_UBUNTU_DEBIAN_BUNDLE",
    "version": version,
    "platform": platform,
    "backendKind": bundle_kind,
    "entrypoint": "scripts/install-ubuntu-debian.sh",
    "javaRequirement": "Java 21 or newer available on PATH, or KAST_JAVA_CMD set",
    "buildCommit": build_commit,
    "artifacts": [
        {
            "role": "cli",
            "path": "bin/kast",
            "sourceSha256": cli_sha,
        },
        {
            "role": backend_role,
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
