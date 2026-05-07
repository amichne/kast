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

need_tool git
need_tool python3
[[ -x /bin/bash ]] || die "Expected /bin/bash to exist"

repo_root="$(resolve_repo_root)"
portable_zip=""

for candidate in "${repo_root}"/kast-cli/build/distributions/kast-cli-*-portable.zip; do
  if [[ -f "$candidate" ]]; then
    portable_zip="$candidate"
    break
  fi
done

[[ -n "$portable_zip" ]] || die "Portable distribution was not found under ${repo_root}/kast-cli/build/distributions"
[[ -f "${repo_root}/kast.sh" ]] || die "Installer script was not found at ${repo_root}/kast.sh"

scratch_dir="${repo_root}/.agent-workflow/smoke-installer"
rm -rf "$scratch_dir"
mkdir -p "$scratch_dir"
platform_id="$(detect_platform_id)"
asset_name="kast-smoke-${platform_id}.zip"
asset_path="${scratch_dir}/${asset_name}"
metadata_path="${scratch_dir}/release.json"
metadata_url="$({ python3 - "$metadata_path" <<'PY'
import sys
from pathlib import Path
print(Path(sys.argv[1]).as_uri())
PY
})"

cp "$portable_zip" "$asset_path"

python3 - "$metadata_path" "$asset_path" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

metadata_path = Path(sys.argv[1])
asset_path = Path(sys.argv[2])
digest = hashlib.sha256(asset_path.read_bytes()).hexdigest()
payload = {
    "tag_name": "v0.0.0-smoke",
    "assets": [
        {
            "name": asset_path.name,
            "browser_download_url": asset_path.as_uri(),
            "digest": f"sha256:{digest}",
        }
    ],
}
metadata_path.write_text(json.dumps(payload), encoding="utf-8")
PY

home_dir="${scratch_dir}/home"
config_dir="${scratch_dir}/config"
workspace_root="${scratch_dir}/workspace"
mkdir -p "$home_dir/.local/bin" "$home_dir/.local/share/kast/instances/legacy-instance" "$home_dir/.agents/skills/kast" "$config_dir" "$workspace_root"
printf '# legacy launcher\n' >"${home_dir}/.local/bin/kast"
printf 'legacy instance\n' >"${home_dir}/.local/share/kast/instances/legacy-instance/marker.txt"
printf 'legacy skill\n' >"${home_dir}/.agents/skills/kast/SKILL.md"
printf 'legacy\n' >"${home_dir}/.agents/skills/kast/.kast-version"
printf '# existing bashrc\n' >"${home_dir}/.bashrc"
printf '[cli]\nbinaryPath = "%s/.local/bin/kast"\n' "$home_dir" >"${config_dir}/config.toml"
git -C "$workspace_root" init -q
installer_content="$(cat "${repo_root}/kast.sh")"
(
  cd "$workspace_root"
  HOME="$home_dir" \
  SHELL=/bin/bash \
  KAST_RELEASE_METADATA_URL="$metadata_url" \
  KAST_CONFIG_HOME="$config_dir" \
  KAST_INSTALL_COMPLETIONS=false \
  KAST_PATH_RC_FILE="${home_dir}/.bashrc" \
  /bin/bash -c "$installer_content" bash install --components=cli --yes --skip-skill
)

installed_launcher="${home_dir}/.kast/bin/kast"
installed_root="${home_dir}/.kast/current"
installed_env="${config_dir}/env"
installed_config="${config_dir}/config.toml"
manifest_path="${home_dir}/.kast/.manifest.json"
installed_extension_root="${workspace_root}/.github"
installed_extension_version="${installed_extension_root}/.kast-copilot-version"
legacy_skill_link="${home_dir}/.agents/skills/kast"
legacy_instances_link="${home_dir}/.local/share/kast/instances"
legacy_bin_link="${home_dir}/.local/bin/kast"

[[ -x "$installed_launcher" ]] || die "Installed launcher is not executable: $installed_launcher"
[[ -L "$installed_root" ]] || die "Current install symlink was not created: $installed_root"
[[ -x "${installed_root}/kast-cli" ]] || die "Installed kast-cli launcher is missing from ${installed_root}"
[[ -f "$installed_env" ]] || die "Config env file was not created: $installed_env"
grep -Fq "export KAST_CONFIG_HOME=\"${config_dir}\"" "$installed_env" || die "KAST_CONFIG_HOME missing from env file"
[[ -f "$installed_config" ]] || die "config.toml was not created: $installed_config"
grep -Fq "binaryPath = \"${installed_launcher}\"" "$installed_config" || die "cli.binaryPath missing from config.toml"
! grep -Fq ".local/bin/kast" "$installed_config" || die "config.toml still points at the legacy launcher path"
[[ -f "$manifest_path" ]] || die "Install manifest was not created: $manifest_path"
[[ -f "${installed_extension_root}/hooks/hooks.json" ]] || die "Copilot extension hooks.json was not installed"
[[ -f "$installed_extension_version" ]] || die "Copilot extension version marker was not installed"
grep -Fq '# Added by the Kast installer' "${home_dir}/.bashrc" || die ".bashrc missing PATH patch"
grep -Fq '# Added by the Kast installer' "${home_dir}/.profile" || die ".profile missing PATH patch for login shells"
[[ -L "$legacy_skill_link" ]] || die "Legacy skill path was not replaced with a symlink"
[[ -L "$legacy_instances_link" ]] || die "Legacy instances path was not replaced with a symlink"
[[ -L "$legacy_bin_link" ]] || die "Legacy launcher path was not replaced with a symlink"
[[ -f "${home_dir}/.kast/lib/skills/kast/SKILL.md" ]] || die "Migrated legacy skill was not moved into ~/.kast"

"$installed_launcher" --help >/dev/null

python3 - "$manifest_path" "$workspace_root" "$home_dir" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
workspace_root = Path(sys.argv[2]).resolve()
home_dir = Path(sys.argv[3]).resolve()
payload = json.loads(manifest_path.read_text(encoding="utf-8"))

assert payload["version"] == "v0.0.0-smoke", payload
assert payload["schemaVersion"] == 3, payload
assert payload["installedAt"], payload
assert payload["platform"], payload
assert "cli" in payload["components"], payload
assert "skill" in payload["components"], payload
assert set(["bin", "current", "releases", "lib/skills/kast"]).issubset(set(payload["managedPaths"])), payload
repo_records = {entry["path"]: entry["copilotExtensionVersion"] for entry in payload["repos"]}
assert repo_records[str(workspace_root)] == "v0.0.0-smoke", payload
patches = {(entry["file"], entry["marker"]) for entry in payload["shellRcPatches"]}
assert (str(home_dir / ".bashrc"), "# Added by the Kast installer") in patches, payload
assert (str(home_dir / ".profile"), "# Added by the Kast installer") in patches, payload
assert (str(home_dir / ".bashrc"), "# >>> kast env >>>") in patches, payload
PY

log "Installer smoke test passed for ${platform_id}"
