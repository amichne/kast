#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

detect_platform_id() {
  local os_name
  local arch_name

  os_name="$(uname -s)"
  arch_name="$(uname -m)"

  case "$os_name:$arch_name" in
    Linux:x86_64)
      printf '%s\n' "linux-x64"
      ;;
    Darwin:x86_64)
      printf '%s\n' "macos-x64"
      ;;
    Darwin:arm64 | Darwin:aarch64)
      printf '%s\n' "macos-arm64"
      ;;
    *)
      die "Unsupported platform: ${os_name} ${arch_name}"
      ;;
  esac
}

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
}

need_tool python3
[[ -x /bin/bash ]] || die "Expected /bin/bash to exist"

repo_root="$(resolve_repo_root)"
portable_zip=""

for candidate in "${repo_root}"/kast/build/distributions/kast-*-portable.zip; do
  if [[ -f "$candidate" ]]; then
    portable_zip="$candidate"
    break
  fi
done

[[ -n "$portable_zip" ]] || die "Portable distribution was not found under ${repo_root}/kast/build/distributions"
[[ -f "${repo_root}/install.sh" ]] || die "Installer script was not found at ${repo_root}/install.sh"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-smoke.XXXXXX")"
platform_id="$(detect_platform_id)"
asset_name="kast-smoke-${platform_id}.zip"
asset_path="${tmp_dir}/${asset_name}"
metadata_path="${tmp_dir}/release.json"
metadata_url="$(
  python3 - "$metadata_path" <<'PY'
import sys
from pathlib import Path

print(Path(sys.argv[1]).as_uri())
PY
)"

cleanup() {
  rm -rf "$tmp_dir"
}

trap cleanup EXIT

cp "$portable_zip" "$asset_path"

python3 - "$metadata_path" "$asset_path" <<'PY'
import json
import sys
from pathlib import Path

metadata_path = Path(sys.argv[1])
asset_path = Path(sys.argv[2])
payload = {
    "tag_name": "v0.0.0-smoke",
    "assets": [
        {
            "name": asset_path.name,
            "browser_download_url": asset_path.as_uri(),
            "digest": "",
        }
    ],
}
metadata_path.write_text(json.dumps(payload), encoding="utf-8")
PY

mkdir -p "${tmp_dir}/home"
installer_content="$(cat "${repo_root}/install.sh")"

HOME="${tmp_dir}/home" \
SHELL=/bin/bash \
KAST_RELEASE_METADATA_URL="$metadata_url" \
KAST_INSTALL_ROOT="${tmp_dir}/install-root" \
KAST_BIN_DIR="${tmp_dir}/bin" \
KAST_SKIP_PATH_UPDATE=true \
KAST_INSTALL_COMPLETIONS=false \
/bin/bash -c "$installer_content"

installed_launcher="${tmp_dir}/bin/kast"
installed_skill_launcher="${tmp_dir}/bin/kast-skilled"
installed_root="${tmp_dir}/install-root/current"

[[ -x "$installed_launcher" ]] || die "Installed launcher is not executable: $installed_launcher"
[[ -x "$installed_skill_launcher" ]] || die "Installed skill launcher is not executable: $installed_skill_launcher"
[[ -L "$installed_root" ]] || die "Current install symlink was not created: $installed_root"
[[ -x "${installed_root}/kast" ]] || die "Installed kast launcher is missing from ${installed_root}"
[[ -x "${installed_root}/bin/kast" ]] || die "Installed kast native binary is missing from ${installed_root}/bin"
[[ -x "${installed_root}/scripts/install-kast-skilled.sh" ]] || die "Installed skill helper is missing from ${installed_root}/scripts"
[[ -f "${installed_root}/share/skills/kast/SKILL.md" ]] || die "Installed packaged skill is missing from ${installed_root}/share/skills/kast"

"$installed_launcher" --help >/dev/null

workspace_root="${tmp_dir}/workspace"
mkdir -p "${workspace_root}/.github"
(
  cd "$workspace_root"
  "$installed_skill_launcher" --yes >/dev/null
)

installed_skill_link="${workspace_root}/.github/skills/kast"
[[ -L "$installed_skill_link" ]] || die "Packaged skill symlink was not created at ${installed_skill_link}"

python3 - "$installed_skill_link" "${installed_root}/share/skills/kast" <<'PY'
import sys
from pathlib import Path

link_path = Path(sys.argv[1])
expected_target = Path(sys.argv[2]).resolve()
actual_target = link_path.resolve()

if actual_target != expected_target:
    raise SystemExit(
        f"Packaged skill symlink target mismatch: expected {expected_target}, got {actual_target}"
    )
PY

log "Installer smoke test passed for ${platform_id}"
