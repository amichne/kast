#!/usr/bin/env bash
set -euo pipefail

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
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$input_path" | awk '{ print $1 }'
    return
  fi
  die "Neither sha256sum nor shasum is available"
}

write_zip_asset() {
  local asset_path="$1"
  local kind="$2"
  python3 - "$asset_path" "$kind" <<'PY'
import io
import stat
import sys
import zipfile
from pathlib import Path

asset_path = Path(sys.argv[1])
kind = sys.argv[2]

def write_entry(archive, name, data, mode=0o644):
    info = zipfile.ZipInfo(name)
    info.external_attr = (stat.S_IFREG | mode) << 16
    archive.writestr(info, data)

def cli_zip(platform, shell=False):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        if shell:
            launcher = b"#!/usr/bin/env bash\necho jvm-wrapper\n"
        elif platform == "linux-x64":
            launcher = b"\x7fELFfake-linux-native\n"
        elif platform == "macos-arm64":
            launcher = bytes.fromhex("cffaedfe") + b"fake-macos-native\n"
        else:
            raise ValueError(platform)
        write_entry(archive, "kast-cli/kast-cli", launcher, 0o755)
    return buffer.getvalue()

def cli_zip_with_runtime(platform):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        if platform == "linux-x64":
            launcher = b"\x7fELFfake-linux-native\n"
        else:
            raise ValueError(platform)
        write_entry(archive, "kast-cli/kast-cli", launcher, 0o755)
        write_entry(archive, "kast-cli/runtime-libs/classpath.txt", b"backend-standalone.jar\n")
        write_entry(archive, "kast-cli/runtime-libs/backend-standalone.jar", b"backend")
    return buffer.getvalue()

def backend_zip(shrunk=False):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        write_entry(archive, "backend-standalone/kast-standalone", b"#!/usr/bin/env bash\n", 0o755)
        if shrunk:
            write_entry(archive, "backend-standalone/runtime-libs/classpath.txt", b"kast-shrunk.jar\n")
            write_entry(archive, "backend-standalone/runtime-libs/kast-shrunk.jar", b"shrunk")
        else:
            write_entry(archive, "backend-standalone/runtime-libs/classpath.txt", b"backend-standalone.jar\n")
            write_entry(archive, "backend-standalone/runtime-libs/backend-standalone.jar", b"backend")
    return buffer.getvalue()

asset_path.parent.mkdir(parents=True, exist_ok=True)
if kind == "cli-linux":
    asset_path.write_bytes(cli_zip("linux-x64"))
elif kind == "cli-macos":
    asset_path.write_bytes(cli_zip("macos-arm64"))
elif kind == "cli-linux-shell":
    asset_path.write_bytes(cli_zip("linux-x64", shell=True))
elif kind == "cli-linux-runtime":
    asset_path.write_bytes(cli_zip_with_runtime("linux-x64"))
elif kind == "standalone":
    asset_path.write_bytes(backend_zip())
elif kind == "standalone-shrunk":
    asset_path.write_bytes(backend_zip(shrunk=True))
elif kind == "headless":
    with zipfile.ZipFile(asset_path, "w") as archive:
        write_entry(archive, "artifacts/kast-cli.zip", cli_zip("linux-x64"))
        write_entry(archive, "artifacts/kast-standalone.zip", backend_zip())
        write_entry(archive, "install.sh", b"#!/usr/bin/env bash\n", 0o755)
elif kind == "intellij":
    with zipfile.ZipFile(asset_path, "w") as archive:
        write_entry(archive, "backend-intellij/lib/backend-intellij.jar", b"plugin")
else:
    raise SystemExit(f"unknown asset kind: {kind}")
PY
}

write_text_asset() {
  local asset_path="$1"
  printf 'contents for %s\n' "$(basename -- "$asset_path")" > "$asset_path"
}

write_expected_assets() {
  write_zip_asset "${release_dir}/kast-${tag}-linux-x64.zip" cli-linux
  write_zip_asset "${release_dir}/kast-${tag}-macos-arm64.zip" cli-macos
  write_zip_asset "${release_dir}/kast-headless-agent-${tag}-linux-x64.zip" headless
  write_zip_asset "${release_dir}/kast-intellij-${tag}.zip" intellij
  write_zip_asset "${release_dir}/kast-standalone-${tag}.zip" standalone
}

write_sha256sums() {
  local release_dir="$1"
  shift

  : > "${release_dir}/SHA256SUMS"
  local asset_name
  for asset_name in "$@"; do
    printf '%s  %s\n' "$(compute_sha256 "${release_dir}/${asset_name}")" "$asset_name" >> "${release_dir}/SHA256SUMS"
  done
}

write_provenance() {
  python3 - "$release_dir" "$tag" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]
entries = [
    ("linux-x64", f"kast-{tag}-linux-x64.zip"),
    ("macos-arm64", f"kast-{tag}-macos-arm64.zip"),
    ("headless-agent-linux-x64", f"kast-headless-agent-{tag}-linux-x64.zip"),
    ("intellij", f"kast-intellij-{tag}.zip"),
    ("standalone", f"kast-standalone-{tag}.zip"),
]
payload = {
    "builds": [
        {
            "platformId": platform,
            "assetName": asset,
            "assetDigest": "sha256:" + hashlib.sha256((release_dir / asset).read_bytes()).hexdigest(),
        }
        for platform, asset in entries
    ]
}
(release_dir / "build-provenance.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

repo_root="$(resolve_repo_root)"
verifier="${repo_root}/scripts/verify-release-assets.sh"
[[ -x "$verifier" ]] || die "release asset verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-verify.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

tag="v9.8.7"
release_dir="${scratch_dir}/release"
mkdir -p "$release_dir"

assets=(
  "kast-${tag}-linux-x64.zip"
  "kast-${tag}-macos-arm64.zip"
  "kast-headless-agent-${tag}-linux-x64.zip"
  "kast-intellij-${tag}.zip"
  "kast-standalone-${tag}.zip"
)

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance

"$verifier" --release-dir "$release_dir" --tag "$tag"

printf 'tampered\n' >> "${release_dir}/${assets[0]}"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/checksum.err"; then
  die "tampered asset unexpectedly verified"
fi
grep -Fq "checksum mismatch" "${scratch_dir}/checksum.err" || die "tampered asset failure did not mention checksum mismatch"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
python3 - "${release_dir}/build-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["builds"] = [
    entry for entry in payload["builds"]
    if entry.get("platformId") != "headless-agent-linux-x64"
]
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/provenance.err"; then
  die "missing provenance unexpectedly verified"
fi
grep -Fq "missing provenance" "${scratch_dir}/provenance.err" || die "missing provenance failure did not mention missing provenance"

write_expected_assets
write_zip_asset "${release_dir}/${assets[0]}" cli-linux-shell
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/native.err"; then
  die "shell launcher CLI asset unexpectedly verified"
fi
grep -Fq "native image" "${scratch_dir}/native.err" || die "shell launcher failure did not mention native image"

write_expected_assets
write_zip_asset "${release_dir}/${assets[0]}" cli-linux-runtime
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/runtime.err"; then
  die "CLI asset with JVM runtime payload unexpectedly verified"
fi
grep -Fq "native CLI-only asset" "${scratch_dir}/runtime.err" || die "runtime payload failure did not mention native CLI-only asset"

write_expected_assets
write_zip_asset "${release_dir}/${assets[4]}" standalone-shrunk
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/shrunk.err"; then
  die "ProGuard/R8-shrunk backend unexpectedly verified"
fi
grep -Fq "ProGuard/R8" "${scratch_dir}/shrunk.err" || die "shrunk backend failure did not mention ProGuard/R8"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
extra_asset="${release_dir}/kast-${tag}-debug.zip"
write_text_asset "$extra_asset"
printf '%s  %s\n' "$(compute_sha256 "$extra_asset")" "$(basename -- "$extra_asset")" >> "${release_dir}/SHA256SUMS"

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/extra.err"; then
  die "extra release asset unexpectedly verified"
fi
grep -Fq "unexpected release asset" "${scratch_dir}/extra.err" || die "extra asset failure did not mention unexpected release asset"

printf '%s\n' "Release asset verifier test passed"
