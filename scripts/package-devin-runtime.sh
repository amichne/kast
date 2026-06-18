#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required tool: $1"
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
import stat
import sys
import zipfile
from pathlib import Path

archive_path = Path(sys.argv[1])
output_dir = Path(sys.argv[2])
output_dir.mkdir(parents=True, exist_ok=True)
resolved_output = output_dir.resolve()

with zipfile.ZipFile(archive_path) as archive:
    for info in archive.infolist():
        member = info.filename
        destination = (output_dir / member).resolve()
        if destination != resolved_output and not str(destination).startswith(str(resolved_output) + "/"):
            raise SystemExit(f"unsafe zip member: {member}")
        mode = info.external_attr >> 16
        member_type = stat.S_IFMT(mode)
        if not info.is_dir() and member_type not in (0, stat.S_IFREG):
            raise SystemExit(f"unsafe zip member type: {member}")
    archive.extractall(output_dir)
PY
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/package-devin-runtime.sh --cli-archive <zip> --backend-archive <zip> --version <version> --output <tar.zst> --manifest-output <json>

Build kast-headless-linux-x64.tar.zst and the kast-runtime-manifest.json
sidecar from the Rust CLI archive and headless backend portable archive.
USAGE
}

repo_root="$(resolve_repo_root)"
cli_archive=""
backend_archive=""
version=""
output_path=""
manifest_output=""
java_version="21"

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
    --manifest-output)
      [[ $# -ge 2 ]] || die "Missing value for --manifest-output"
      manifest_output="$2"; shift 2 ;;
    --manifest-output=*)
      manifest_output="${1#--manifest-output=}"; shift ;;
    --java-version)
      [[ $# -ge 2 ]] || die "Missing value for --java-version"
      java_version="$2"; shift 2 ;;
    --java-version=*)
      java_version="${1#--java-version=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$cli_archive" ]] || { usage; die "--cli-archive is required"; }
[[ -n "$backend_archive" ]] || { usage; die "--backend-archive is required"; }
[[ -n "$version" ]] || { usage; die "--version is required"; }
[[ -n "$output_path" ]] || { usage; die "--output is required"; }
[[ -n "$manifest_output" ]] || { usage; die "--manifest-output is required"; }
[[ -f "$cli_archive" ]] || die "CLI archive not found: $cli_archive"
[[ -f "$backend_archive" ]] || die "Backend archive not found: $backend_archive"

need_tool python3
need_tool tar
need_tool zstd

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-runtime.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

cli_extract="${tmp_dir}/cli"
backend_extract="${tmp_dir}/backend"
staging_root="${tmp_dir}/runtime"
mkdir -p "$cli_extract" "$backend_extract" "$staging_root" \
  "$(dirname -- "$output_path")" \
  "$(dirname -- "$manifest_output")"

extract_zip_archive "$cli_archive" "$cli_extract"
extract_zip_archive "$backend_archive" "$backend_extract"

backend_root="${backend_extract}/backend-headless"
[[ -f "${cli_extract}/kast" ]] || die "CLI archive must contain kast at its root"
[[ -d "$backend_root" ]] || die "Backend archive must contain backend-headless/"
[[ -f "${backend_root}/runtime-libs/classpath.txt" ]] || die "Backend archive missing runtime-libs/classpath.txt"
[[ -f "${backend_root}/idea-home/lib/nio-fs.jar" ]] || die "Backend archive missing idea-home/lib/nio-fs.jar"
[[ -f "${backend_root}/idea-home/modules/module-descriptors.dat" ]] || die "Backend archive missing idea-home/modules/module-descriptors.dat"
[[ -d "${backend_root}/idea-home/plugins/kast-headless" ]] || die "Backend archive missing idea-home/plugins/kast-headless"

mkdir -p "${staging_root}/bin" "${staging_root}/lib" "${staging_root}/plugins"
cp "${cli_extract}/kast" "${staging_root}/bin/kast"
chmod 755 "${staging_root}/bin/kast"
cp -R "${backend_root}/runtime-libs" "${staging_root}/lib/runtime-libs"
cp -R "${backend_root}/idea-home" "${staging_root}/idea"
cp -R "${backend_root}/idea-home/plugins/." "${staging_root}/plugins/"

rm -f "$output_path"
COPYFILE_DISABLE=1 tar --no-xattrs --zstd -C "$staging_root" -cf "$output_path" .
artifact_sha="$(compute_sha256 "$output_path")"
sidecar_path="${output_path%.tar.zst}.sha256"
if [[ "$sidecar_path" == "$output_path" ]]; then
  sidecar_path="${output_path}.sha256"
fi
printf '%s  %s\n' "$artifact_sha" "$(basename -- "$output_path")" > "$sidecar_path"

python3 - "$repo_root" "$manifest_output" "$version" "$java_version" "$artifact_sha" <<'PY'
import json
import re
import subprocess
import sys
from pathlib import Path

repo_root = Path(sys.argv[1])
manifest_output = Path(sys.argv[2])
version = sys.argv[3].removeprefix("v")
java_version = sys.argv[4]
artifact_sha = sys.argv[5]
catalog = (repo_root / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
release_state = json.loads((repo_root / "packaging" / "homebrew" / "release-state.json").read_text(encoding="utf-8"))

def version_value(name: str) -> str:
    match = re.search(rf'^{re.escape(name)}\s*=\s*"([^"]+)"$', catalog, re.MULTILINE)
    if not match:
        raise SystemExit(f"gradle/libs.versions.toml is missing {name}")
    return match.group(1)

git_sha = subprocess.check_output(["git", "-C", str(repo_root), "rev-parse", "HEAD"], text=True).strip()
payload = {
    "schemaVersion": 1,
    "kastVersion": version,
    "kastGitSha": git_sha,
    "os": "linux",
    "arch": "x64",
    "javaVersion": java_version,
    "intellijBuild": version_value("idea"),
    "kotlinPluginVersion": version_value("kotlin"),
    "kastIndexSchemaVersion": str(release_state["source_index_schema_version"]),
    "artifactSha256": artifact_sha,
}
manifest_output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

printf 'Wrote %s\n' "$output_path" >&2
printf 'Wrote %s\n' "$sidecar_path" >&2
printf 'Wrote %s\n' "$manifest_output" >&2
