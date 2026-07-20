#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

compute_sha256() {
  local input_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$input_path" | awk '{ print $1 }'
    return
  fi
  shasum -a 256 "$input_path" | awk '{ print $1 }'
}

verify_sidecar() {
  local sidecar_path="$1"
  python3 - "$sidecar_path" <<'PY'
import hashlib
import sys
from pathlib import Path

sidecar = Path(sys.argv[1])
line = sidecar.read_text(encoding="utf-8").strip()
parts = line.split()
if len(parts) != 2:
    raise SystemExit(f"invalid checksum sidecar: {sidecar}")
expected, asset_name = parts
asset = sidecar.parent / asset_name
if not asset.is_file():
    raise SystemExit(f"checksum sidecar names missing asset: {asset_name}")
actual = hashlib.sha256(asset.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit(f"checksum sidecar mismatch for {asset_name}")
PY
}

write_fixture_cli_zip() {
  local output="$1"
  local root="$2"
  mkdir -p "$root"
  cat > "${root}/kast" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  --version|version) printf '%s\n' 'Kast CLI 9.8.7' ;;
  doctor) printf '%s\n' 'doctor ok' ;;
  *) printf '%s\n' "fixture kast $*" ;;
esac
SH
  chmod 755 "${root}/kast"
  (cd "$root" && zip -9 -q "$output" kast)
}

write_fixture_backend_zip() {
  local output="$1"
  local root="$2"
  mkdir -p \
    "${root}/backend-headless/runtime-libs" \
    "${root}/backend-headless/idea-home/lib" \
    "${root}/backend-headless/idea-home/modules" \
    "${root}/backend-headless/idea-home/plugins/kast-headless/lib"
  printf '%s\n' 'fixture-classpath' > "${root}/backend-headless/runtime-libs/classpath.txt"
  : > "${root}/backend-headless/idea-home/lib/nio-fs.jar"
  : > "${root}/backend-headless/idea-home/modules/module-descriptors.dat"
  : > "${root}/backend-headless/idea-home/plugins/kast-headless/lib/backend.jar"
  (cd "$root" && zip -9 -q -r "$output" backend-headless)
}

write_unsafe_zip() {
  local output="$1"
  local kind="$2"
  python3 - "$output" "$kind" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

output = Path(sys.argv[1])
kind = sys.argv[2]
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w") as archive:
    if kind == "path":
        archive.writestr("../escape", "unsafe")
    elif kind == "symlink":
        info = zipfile.ZipInfo("kast")
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive.writestr(info, "/bin/sh")
    else:
        raise SystemExit(f"unknown unsafe zip kind: {kind}")
PY
}

repo_root="$(resolve_repo_root)"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-headless-packagers.XXXXXX")"
scratch_dir="$(cd -- "$scratch_dir" && pwd)"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

cli_zip="${scratch_dir}/kast-v9.8.7-linux-x64.zip"
backend_zip="${scratch_dir}/backend-headless-9.8.7-portable.zip"
write_fixture_cli_zip "$cli_zip" "${scratch_dir}/cli"
write_fixture_backend_zip "$backend_zip" "${scratch_dir}/backend"

unsafe_path_zip="${scratch_dir}/unsafe-path.zip"
write_unsafe_zip "$unsafe_path_zip" path
if "${repo_root}/scripts/extract-safe-zip.py" \
  "$unsafe_path_zip" \
  "${scratch_dir}/unsafe-path-extract" \
  >"${scratch_dir}/unsafe-extractor-path.out" 2>"${scratch_dir}/unsafe-extractor-path.err"; then
  die "safe ZIP extractor accepted path traversal"
fi
grep -Fq "unsafe zip member" "${scratch_dir}/unsafe-extractor-path.err" \
  || die "safe ZIP extractor path failure did not identify the unsafe member"
if "${repo_root}/scripts/package-headless-runtime.sh" \
  --cli-archive "$unsafe_path_zip" \
  --backend-archive "$backend_zip" \
  --version 9.8.7 \
  --output "${scratch_dir}/unsafe-path.tar.zst" \
  --manifest-output "${scratch_dir}/unsafe-path-manifest.json" \
  >"${scratch_dir}/unsafe-path.out" 2>"${scratch_dir}/unsafe-path.err"; then
  die "unsafe path zip unexpectedly packaged"
fi
grep -Fq "unsafe zip member" "${scratch_dir}/unsafe-path.err" || die "unsafe path zip failure did not mention unsafe member"

unsafe_symlink_zip="${scratch_dir}/unsafe-symlink.zip"
write_unsafe_zip "$unsafe_symlink_zip" symlink
if "${repo_root}/scripts/extract-safe-zip.py" \
  "$unsafe_symlink_zip" \
  "${scratch_dir}/unsafe-symlink-extract" \
  >"${scratch_dir}/unsafe-extractor-symlink.out" 2>"${scratch_dir}/unsafe-extractor-symlink.err"; then
  die "safe ZIP extractor accepted a symlink"
fi
grep -Fq "unsafe zip member type" "${scratch_dir}/unsafe-extractor-symlink.err" \
  || die "safe ZIP extractor symlink failure did not identify the unsafe type"
if "${repo_root}/scripts/package-headless-runtime.sh" \
  --cli-archive "$unsafe_symlink_zip" \
  --backend-archive "$backend_zip" \
  --version 9.8.7 \
  --output "${scratch_dir}/unsafe-symlink.tar.zst" \
  --manifest-output "${scratch_dir}/unsafe-symlink-manifest.json" \
  >"${scratch_dir}/unsafe-symlink.out" 2>"${scratch_dir}/unsafe-symlink.err"; then
  die "unsafe symlink zip unexpectedly packaged"
fi

"${repo_root}/scripts/extract-safe-zip.py" \
  "$cli_zip" \
  "${scratch_dir}/safe-cli-extract"
[[ -x "${scratch_dir}/safe-cli-extract/kast" ]] \
  || die "safe ZIP extractor must preserve the CLI executable bit"
grep -Fq "unsafe zip member type" "${scratch_dir}/unsafe-symlink.err" || die "unsafe symlink zip failure did not mention unsafe type"

runtime_artifact="${scratch_dir}/kast-headless-linux-x64.tar.zst"
runtime_manifest="${scratch_dir}/kast-runtime-manifest.json"
"${repo_root}/scripts/package-headless-runtime.sh" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_zip" \
  --version 9.8.7 \
  --output "$runtime_artifact" \
  --manifest-output "$runtime_manifest"

[[ -f "$runtime_artifact" ]] || die "runtime artifact was not written"
[[ -f "${scratch_dir}/kast-headless-linux-x64.sha256" ]] || die "runtime checksum sidecar was not written"
[[ -f "$runtime_manifest" ]] || die "runtime manifest was not written"
verify_sidecar "${scratch_dir}/kast-headless-linux-x64.sha256"
tar --zstd -tf "$runtime_artifact" | grep -Fq './bin/kast' || die "runtime artifact missing bin/kast"
tar --zstd -tf "$runtime_artifact" | grep -Fq './lib/runtime-libs/classpath.txt' || die "runtime artifact missing runtime libs"
python3 - "$runtime_manifest" "$runtime_artifact" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
artifact = Path(sys.argv[2])
assert manifest["kastVersion"] == "9.8.7"
assert manifest["os"] == "linux"
assert manifest["arch"] == "x64"
assert manifest["artifactSha256"] == hashlib.sha256(artifact.read_bytes()).hexdigest()
PY

gradle_home="${scratch_dir}/gradle-home"
mkdir -p "${gradle_home}/caches/modules-2/files-2.1/example/module"
printf '%s\n' 'fixture' > "${gradle_home}/caches/modules-2/files-2.1/example/module/artifact.pom"
printf '%s\n' 'lock' > "${gradle_home}/caches/modules-2/modules-2.lock"
printf '%s\n' 'gc' > "${gradle_home}/caches/modules-2/gc.properties"
cache_artifact="${scratch_dir}/gradle-ro-dep-cache.tar.zst"
"${repo_root}/scripts/package-gradle-ro-cache.sh" \
  --gradle-user-home "$gradle_home" \
  --output "$cache_artifact"
[[ -f "$cache_artifact" ]] || die "Gradle cache artifact was not written"
[[ -f "${scratch_dir}/gradle-ro-dep-cache.sha256" ]] || die "Gradle cache checksum sidecar was not written"
verify_sidecar "${scratch_dir}/gradle-ro-dep-cache.sha256"
tar --zstd -tf "$cache_artifact" | grep -Fq 'gradle-ro/modules-2/files-2.1/example/module/artifact.pom' \
  || die "Gradle cache artifact missing modules-2 content"
if tar --zstd -tf "$cache_artifact" | grep -E '(\.lock|gc\.properties)$' >/dev/null; then
  die "Gradle cache artifact contains lock or GC metadata"
fi
grep -Fq "$(compute_sha256 "$cache_artifact")" "${scratch_dir}/gradle-ro-dep-cache.sha256" \
  || die "Gradle cache checksum sidecar does not match"

printf '%s\n' "Headless runtime packager contracts passed"
