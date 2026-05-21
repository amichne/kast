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
need_tool unzip

scratch_dir="$(absolute_path "$(mktemp -d "${TMPDIR:-/tmp}/kast-agent-bundle-smoke.XXXXXX")")"
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
extract_dir="${scratch_dir}/extract"

mkdir -p \
  "${artifact_dir}" \
  "${cli_tree}/kast-cli" \
  "${backend_tree}/backend-standalone/runtime-libs" \
  "${workspace_root}" \
  "${home_dir}" \
  "${extract_dir}"

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
      mkdir -p \
        "${target_dir}/agents" \
        "${target_dir}/extensions/kast/scripts" \
        "${target_dir}/extensions/kotlin-gradle-loop"
      printf '%s\n' "# fake orchestrator" > "${target_dir}/agents/kast-orchestrator.md"
      printf '%s\n' "export default {};" > "${target_dir}/extensions/kast/extension.mjs"
      printf '%s\n' "#!/usr/bin/env bash" "printf '%s\n' fake-kast" > "${target_dir}/extensions/kast/scripts/resolve-kast.sh"
      chmod +x "${target_dir}/extensions/kast/scripts/resolve-kast.sh"
      printf '%s\n' "export default {};" > "${target_dir}/extensions/kotlin-gradle-loop/extension.mjs"
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
bundle_zip="${artifact_dir}/kast-headless-agent-smoke-linux-x64.zip"
zip_dir "$cli_zip" "$cli_tree"
zip_dir "$backend_zip" "$backend_tree"

"${repo_root}/scripts/package-headless-agent-bundle.sh" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_zip" \
  --version smoke \
  --platform-id linux-x64 \
  --output "$bundle_zip"

[[ -f "$bundle_zip" ]] || die "Bundle zip was not created: $bundle_zip"
unzip -q "$bundle_zip" -d "$extract_dir"

[[ -x "${extract_dir}/install.sh" ]] || die "Bundle install.sh is missing or not executable"
[[ -f "${extract_dir}/README.md" ]] || die "Bundle README.md is missing"
[[ -f "${extract_dir}/manifest.json" ]] || die "Bundle manifest.json is missing"
grep -Fq "$(compute_sha256 "$cli_zip")  artifacts/kast-cli.zip" "${extract_dir}/checksums.txt" || die "CLI checksum missing from bundle"
grep -Fq "$(compute_sha256 "$backend_zip")  artifacts/kast-standalone.zip" "${extract_dir}/checksums.txt" || die "Backend checksum missing from bundle"

git -C "$workspace_root" init -q
(
  cd "$workspace_root"
  HOME="$home_dir" \
  SHELL=/bin/bash \
  KAST_AGENT_INSTALL_ROOT="$install_root" \
  KAST_AGENT_WORKSPACE="$workspace_root" \
  "${extract_dir}/install.sh"
)

installed_launcher="${install_root}/bin/kast"
config_file="${install_root}/config/config.toml"
env_file="${install_root}/kast-env.sh"
runtime_libs_dir="${install_root}/backends/current/runtime-libs"
skill_dir="${install_root}/lib/skills/kast"
extension_marker="${workspace_root}/.github/.kast-copilot-version"

[[ -x "$installed_launcher" ]] || die "Missing executable launcher: $installed_launcher"
[[ -f "$config_file" ]] || die "Missing config file: $config_file"
grep -Fq "binaryPath = \"${installed_launcher}\"" "$config_file" || die "config.toml does not point at contained launcher"
[[ -d "$runtime_libs_dir" ]] || die "Missing standalone runtime libs: $runtime_libs_dir"
[[ -f "${skill_dir}/SKILL.md" ]] || die "Missing installed skill: ${skill_dir}/SKILL.md"
[[ -f "$extension_marker" ]] || die "Missing Copilot extension marker: $extension_marker"
[[ -f "${workspace_root}/.github/agents/kast-orchestrator.md" ]] || die "Missing Copilot agent"
[[ -f "${workspace_root}/.github/hooks/hooks.json" ]] || die "Missing Copilot hooks"
[[ -f "${workspace_root}/.github/extensions/kast/extension.mjs" ]] || die "Missing kast Copilot extension"
[[ -x "${workspace_root}/.github/extensions/kast/scripts/resolve-kast.sh" ]] || die "Missing executable kast resolver"
[[ -f "${workspace_root}/.github/extensions/kotlin-gradle-loop/extension.mjs" ]] || die "Missing Kotlin Gradle loop extension"
[[ -f "$env_file" ]] || die "Missing sourceable env file: $env_file"
"$installed_launcher" --help >/dev/null

python3 - "${extract_dir}/manifest.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["schemaVersion"] == 1, payload
assert payload["kind"] == "KAST_HEADLESS_AGENT_BUNDLE", payload
assert payload["platform"] == "linux-x64", payload
assert payload["version"] == "smoke", payload
assert payload["entrypoint"] == "install.sh", payload
artifacts = {entry["path"]: entry for entry in payload["artifacts"]}
assert set(artifacts) == {"artifacts/kast-cli.zip", "artifacts/kast-standalone.zip"}, payload
PY

printf '%s\n' "Headless agent bundle smoke test passed"
