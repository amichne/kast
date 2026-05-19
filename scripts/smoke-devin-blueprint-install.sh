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

file_uri() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

print(Path(sys.argv[1]).resolve().as_uri())
PY
}

absolute_path() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

print(Path(sys.argv[1]).resolve())
PY
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

repo_root="$(resolve_repo_root)"

need_tool git
need_tool python3
need_tool curl

scratch_dir="$(absolute_path "$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-smoke.XXXXXX")")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

artifact_dir="${scratch_dir}/artifacts"
cli_tree="${scratch_dir}/cli"
backend_tree="${scratch_dir}/backend"
workspace_root="${scratch_dir}/workspace"
home_dir="${scratch_dir}/home"
install_root="${scratch_dir}/contained-kast"

mkdir -p \
  "${artifact_dir}" \
  "${cli_tree}/kast-cli" \
  "${backend_tree}/backend-standalone/runtime-libs" \
  "${workspace_root}" \
  "${home_dir}"

cat > "${cli_tree}/kast-cli/kast-cli" <<'FAKE_KAST'
#!/usr/bin/env bash
set -euo pipefail

command_name="${1:-}"
if [[ -z "$command_name" || "$command_name" == "--help" || "$command_name" == "help" ]]; then
  printf '%s\n' "fake kast help"
  exit 0
fi

if [[ "$command_name" == "install" ]]; then
  subcommand="${2:-}"
  shift 2 || true
  case "$subcommand" in
    skill)
      target_dir=""
      name="kast"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --target-dir=*) target_dir="${1#--target-dir=}" ;;
          --target-dir) target_dir="$2"; shift ;;
          --name=*) name="${1#--name=}" ;;
          --name) name="$2"; shift ;;
        esac
        shift || true
      done
      [[ -n "$target_dir" ]] || { printf '%s\n' "missing --target-dir" >&2; exit 1; }
      mkdir -p "${target_dir}/${name}"
      printf '%s\n' "# fake skill" > "${target_dir}/${name}/SKILL.md"
      printf '%s\n' "fake" > "${target_dir}/${name}/.kast-version"
      exit 0
      ;;
    copilot-extension)
      target_dir=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --target-dir=*) target_dir="${1#--target-dir=}" ;;
          --target-dir) target_dir="$2"; shift ;;
        esac
        shift || true
      done
      [[ -n "$target_dir" ]] || { printf '%s\n' "missing --target-dir" >&2; exit 1; }
      mkdir -p "${target_dir}/hooks"
      printf '%s\n' '{"version":1,"hooks":{}}' > "${target_dir}/hooks/hooks.json"
      printf '%s\n' "fake" > "${target_dir}/.kast-copilot-version"
      exit 0
      ;;
  esac
fi

printf 'unexpected fake kast invocation: %s\n' "$*" >&2
exit 1
FAKE_KAST
chmod +x "${cli_tree}/kast-cli/kast-cli"

cat > "${backend_tree}/backend-standalone/kast-standalone" <<'FAKE_BACKEND'
#!/usr/bin/env bash
printf '%s\n' "fake backend"
FAKE_BACKEND
chmod +x "${backend_tree}/backend-standalone/kast-standalone"
printf '%s\n' "fake runtime lib" > "${backend_tree}/backend-standalone/runtime-libs/fake.jar"

cli_zip="${artifact_dir}/kast-cli-internal.zip"
backend_zip="${artifact_dir}/kast-standalone-internal.zip"
zip_dir "$cli_zip" "$cli_tree"
zip_dir "$backend_zip" "$backend_tree"

git -C "$workspace_root" init -q

HOME="$home_dir" \
SHELL=/bin/bash \
KAST_DEVIN_CLI_URL="$(file_uri "$cli_zip")" \
KAST_DEVIN_BACKEND_URL="$(file_uri "$backend_zip")" \
KAST_DEVIN_CLI_SHA256="$(compute_sha256 "$cli_zip")" \
KAST_DEVIN_BACKEND_SHA256="$(compute_sha256 "$backend_zip")" \
KAST_DEVIN_INSTALL_ROOT="$install_root" \
KAST_DEVIN_WORKSPACE="$workspace_root" \
"${repo_root}/scripts/devin-blueprint-install.sh"

installed_launcher="${install_root}/bin/kast"
config_file="${install_root}/config/config.toml"
env_file="${install_root}/kast-env.sh"
manifest_path="${install_root}/.manifest.json"
runtime_libs_dir="${install_root}/backends/current/runtime-libs"
skill_dir="${install_root}/lib/skills/kast"
extension_marker="${workspace_root}/.github/.kast-copilot-version"

[[ -x "$installed_launcher" ]] || die "Missing executable launcher: $installed_launcher"
[[ -f "$config_file" ]] || die "Missing config file: $config_file"
grep -Fq "binaryPath = \"${installed_launcher}\"" "$config_file" || die "config.toml does not point at contained launcher"
grep -Fq "installRoot = \"${install_root}\"" "$config_file" || die "config.toml does not use contained install root"
[[ -d "$runtime_libs_dir" ]] || die "Missing standalone runtime libs: $runtime_libs_dir"
[[ -f "${skill_dir}/SKILL.md" ]] || die "Missing installed skill: ${skill_dir}/SKILL.md"
[[ -f "$extension_marker" ]] || die "Missing Copilot extension marker: $extension_marker"
[[ -f "${workspace_root}/.github/hooks/hooks.json" ]] || die "Missing Copilot hooks"
[[ -f "$env_file" ]] || die "Missing sourceable env file: $env_file"
grep -Fq "export KAST_CONFIG_HOME=\"${install_root}/config\"" "$env_file" || die "env file missing KAST_CONFIG_HOME"
grep -Fq "export PATH=\"${install_root}/bin:\$PATH\"" "$env_file" || die "env file missing PATH export"
[[ -f "$manifest_path" ]] || die "Missing install manifest: $manifest_path"

"$installed_launcher" --help >/dev/null

python3 - "$manifest_path" "$workspace_root" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
workspace_root = str(Path(sys.argv[2]).resolve())
components = set(payload.get("components", []))
assert {"cli", "backend", "skill"}.issubset(components), payload
repos = {entry["path"] for entry in payload.get("repos", [])}
assert workspace_root in repos, payload
PY

if HOME="$home_dir" \
  SHELL=/bin/bash \
  KAST_DEVIN_CLI_URL="$(file_uri "$cli_zip")" \
  KAST_DEVIN_BACKEND_URL="$(file_uri "$backend_zip")" \
  KAST_DEVIN_CLI_SHA256="0000000000000000000000000000000000000000000000000000000000000000" \
  KAST_DEVIN_BACKEND_SHA256="$(compute_sha256 "$backend_zip")" \
  KAST_DEVIN_INSTALL_ROOT="${scratch_dir}/bad-checksum-install" \
  KAST_DEVIN_WORKSPACE="$workspace_root" \
  "${repo_root}/scripts/devin-blueprint-install.sh" >/dev/null 2>&1; then
  die "Bad CLI checksum unexpectedly succeeded"
fi

printf '%s\n' "Devin Blueprint installer smoke test passed"
