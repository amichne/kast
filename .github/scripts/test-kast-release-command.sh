#!/usr/bin/env bash
set -euo pipefail

test_die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

repo_root="$(resolve_repo_root)"

set -- help
source "${repo_root}/kast.sh" >/dev/null 2>&1
set --

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-command.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

PORTABLE_ZIP_DIR="${scratch_dir}/portable"
DIST_ROOT="${scratch_dir}/dist"
GRADLEW="${scratch_dir}/gradlew"
mkdir -p "$PORTABLE_ZIP_DIR" "$DIST_ROOT"
printf '#!/usr/bin/env bash\nexit 0\n' > "$GRADLEW"
chmod +x "$GRADLEW"

portable_zip="${PORTABLE_ZIP_DIR}/kast-cli-9.8.7-portable.zip"
python3 - "$portable_zip" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

zip_path = Path(sys.argv[1])

def write_entry(archive, name, data, mode=0o644):
    info = zipfile.ZipInfo(name)
    info.external_attr = (stat.S_IFREG | mode) << 16
    archive.writestr(info, data)

with zipfile.ZipFile(zip_path, "w") as archive:
    write_entry(archive, "kast-cli/kast-cli", b"#!/usr/bin/env bash\necho jvm-wrapper\n", 0o755)
    write_entry(archive, "kast-cli/libs/kast-cli-all.jar", b"jar")
    write_entry(archive, "kast-cli/runtime-libs/classpath.txt", b"backend-standalone.jar\n")
    write_entry(archive, "kast-cli/runtime-libs/backend-standalone.jar", b"backend")
PY

native_bin="${scratch_dir}/kast-native"
printf '\177ELFfake-native\n' > "$native_bin"
chmod +x "$native_bin"

if (
    cmd_release \
    --tag v9.8.7 \
    --platform-id linux-x64 \
    --skip-build \
    --shrink
  ) >/dev/null 2>"${scratch_dir}/shrink.err"; then
  test_die "release command accepted --shrink for a published asset"
fi
grep -Eq 'ProGuard|R8|shrink' "${scratch_dir}/shrink.err" \
  || test_die "release-time shrink rejection did not explain the ProGuard/R8 constraint"

cmd_release \
  --tag v9.8.7 \
  --platform-id linux-x64 \
  --skip-build \
  --native-binary "$native_bin" >/dev/null

asset_path="${DIST_ROOT}/kast-v9.8.7-linux-x64.zip"
[[ -f "$asset_path" ]] || test_die "release command did not create ${asset_path}"

python3 - "$asset_path" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

asset_path = Path(sys.argv[1])
with zipfile.ZipFile(asset_path) as archive:
    names = archive.namelist()
    info = archive.getinfo("kast-cli/kast-cli")
    payload = archive.read("kast-cli/kast-cli")
    mode = (info.external_attr >> 16) & 0o777

if any(name.startswith(("kast-cli/runtime-libs/", "kast-cli/libs/")) for name in names):
    raise SystemExit("native release asset carried JVM payload directories")
if payload != b"\x7fELFfake-native\n":
    raise SystemExit("release asset did not contain the native binary payload")
if not mode & stat.S_IXUSR:
    raise SystemExit("release asset native binary is not executable")
if info.compress_type != zipfile.ZIP_DEFLATED:
    raise SystemExit("release asset native binary was not deflated")
PY

printf '%s\n' "Kast release command test passed"
