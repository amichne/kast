#!/usr/bin/env bash
set -euo pipefail

resolve_script_dir() {
  local bash_source="${BASH_SOURCE[0]-}"
  if [[ -z "$bash_source" ]]; then
    printf '%s\n' ""
    return
  fi

  cd -- "$(dirname -- "$bash_source")" >/dev/null 2>&1 && pwd
}

readonly SCRIPT_DIR="$(resolve_script_dir)"
readonly DEFAULT_RELEASE_REPO="amichne/kast"
readonly GITHUB_API_ACCEPT="Accept: application/vnd.github+json"
readonly GITHUB_API_VERSION="X-GitHub-Api-Version: 2022-11-28"
readonly PATH_MARKER="# Added by the Kast installer"
readonly COMPLETION_START_MARKER="# >>> Kast completion >>>"
readonly COMPLETION_END_MARKER="# <<< Kast completion <<<"

tmp_dir=""

supports_color() {
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then
    return 0
  fi
  if [[ -n "${NO_COLOR:-}" ]]; then
    return 1
  fi
  if [[ ! -t 2 ]]; then
    return 1
  fi
  [[ "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"
  shift

  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
    return
  fi

  printf '%s' "$*"
}

log_line() {
  local label="$1"
  local message="$2"
  printf '%s %s\n' "$label" "$message" >&2
}

log() {
  log_line "$(colorize '2' '│')" "$*"
}

log_section() {
  printf '\n%s\n' "$(colorize '1;36' "$*")" >&2
}

log_step() {
  log_line "$(colorize '1;34' '›')" "$*"
}

log_success() {
  log_line "$(colorize '1;32' '✓')" "$*"
}

log_note() {
  log_line "$(colorize '33' '•')" "$*"
}

log_prompt() {
  printf '%s %s' "$(colorize '1;34' '?')" "$*" >/dev/tty
}

die() {
  log_line "$(colorize '1;31' '✕')" "$*"
  exit 1
}

cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
}

trap cleanup EXIT

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
}

resolve_release_repo() {
  if [[ -n "${KAST_RELEASE_REPO:-}" ]]; then
    printf '%s\n' "$KAST_RELEASE_REPO"
    return
  fi

  if [[ -z "$SCRIPT_DIR" ]]; then
    printf '%s\n' "$DEFAULT_RELEASE_REPO"
    return
  fi

  if ! command -v git >/dev/null 2>&1; then
    printf '%s\n' "$DEFAULT_RELEASE_REPO"
    return
  fi

  local origin
  origin="$(git -C "$SCRIPT_DIR" config --get remote.origin.url 2>/dev/null || true)"

  if [[ "$origin" =~ ^git@github\.com:([^/]+)/([^.]+)(\.git)?$ ]]; then
    printf '%s/%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    return
  fi

  if [[ "$origin" =~ ^https://github\.com/([^/]+)/([^.]+)(\.git)?$ ]]; then
    printf '%s/%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    return
  fi

  printf '%s\n' "$DEFAULT_RELEASE_REPO"
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

resolve_java_bin() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    local candidate="${JAVA_HOME}/bin/java"
    [[ -x "$candidate" ]] || die "JAVA_HOME is set but does not contain an executable java binary"
    printf '%s\n' "$candidate"
    return
  fi

  command -v java >/dev/null 2>&1 || die "Java 21 is required. Install Java 21 and rerun the installer."
  command -v java
}

assert_java_21() {
  local java_bin="$1"
  local spec_version

  spec_version="$(
    "$java_bin" -XshowSettings:properties -version 2>&1 |
      awk -F'= ' '/java.specification.version =/ { print $2; exit }'
  )"

  [[ -n "$spec_version" ]] || die "Could not determine the installed Java version"

  local major_version="${spec_version%%.*}"
  if [[ "$major_version" -lt 21 ]]; then
    die "Kast requires Java 21 or newer. Found Java specification version $spec_version."
  fi
}

download_file() {
  local url="$1"
  local output_path="$2"

  curl \
    --fail \
    --location \
    --retry 3 \
    --retry-delay 2 \
    --silent \
    --show-error \
    --output "$output_path" \
    "$url"
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

  die "Neither sha256sum nor shasum is available for checksum verification"
}

extract_release_metadata() {
  local metadata_path="$1"
  local platform_id="$2"

  python3 - "$metadata_path" "$platform_id" <<'PY'
import json
import re
import sys
from pathlib import Path

metadata_path = Path(sys.argv[1])
platform_id = sys.argv[2]
if not metadata_path.is_file():
    raise SystemExit(f"Release metadata file was not found: {metadata_path}")
release = json.loads(metadata_path.read_text(encoding="utf-8"))
pattern = re.compile(rf"^kast-.*-{re.escape(platform_id)}\.zip$")

for asset in release.get("assets", []):
    name = asset.get("name", "")
    if pattern.match(name):
        print(release.get("tag_name", ""))
        print(name)
        print(asset.get("browser_download_url", ""))
        print(asset.get("digest", ""))
        break
else:
    asset_names = ", ".join(asset.get("name", "<unnamed>") for asset in release.get("assets", []))
    raise SystemExit(
        f"No release asset matched platform '{platform_id}'. "
        f"Available assets: {asset_names or '<none>'}"
    )
PY
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

with zipfile.ZipFile(archive_path) as archive:
    archive.extractall(output_dir)
PY
}

write_install_metadata() {
  local output_path="$1"
  local release_repo="$2"
  local release_tag="$3"
  local platform_id="$4"
  local archive_name="$5"
  local archive_source="$6"

  python3 - "$output_path" "$release_repo" "$release_tag" "$platform_id" "$archive_name" "$archive_source" <<'PY'
import json
import sys
from pathlib import Path

output_path = Path(sys.argv[1])
payload = {
    "releaseRepo": sys.argv[2],
    "releaseTag": sys.argv[3],
    "platformId": sys.argv[4],
    "archiveName": sys.argv[5],
    "archiveSource": sys.argv[6],
}
output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

path_contains() {
  local target_dir="$1"
  local path_entry

  IFS=':' read -r -a entries <<<"${PATH:-}"
  for path_entry in "${entries[@]}"; do
    if [[ "$path_entry" == "$target_dir" ]]; then
      return 0
    fi
  done

  return 1
}

resolve_shell_rc_file() {
  if [[ -n "${KAST_PATH_RC_FILE:-}" ]]; then
    printf '%s\n' "$KAST_PATH_RC_FILE"
    return
  fi

  local shell_name="${SHELL##*/}"
  case "$shell_name" in
    zsh)
      printf '%s\n' "${HOME}/.zshrc"
      ;;
    bash)
      if [[ -f "${HOME}/.bashrc" ]]; then
        printf '%s\n' "${HOME}/.bashrc"
      else
        printf '%s\n' "${HOME}/.bash_profile"
      fi
      ;;
    *)
      printf '%s\n' ""
      ;;
  esac
}

resolve_shell_name() {
  local shell_name="${SHELL##*/}"
  case "$shell_name" in
    bash | zsh)
      printf '%s\n' "$shell_name"
      ;;
    *)
      printf '%s\n' ""
      ;;
  esac
}

ensure_bin_dir_on_path() {
  local bin_dir="$1"

  if path_contains "$bin_dir"; then
    return
  fi

  if [[ "${KAST_SKIP_PATH_UPDATE:-false}" == "true" ]]; then
    log_note "Add ${bin_dir} to PATH before running kast."
    return
  fi

  local rc_file
  rc_file="$(resolve_shell_rc_file)"

  if [[ -z "$rc_file" ]]; then
    log_note "Add ${bin_dir} to PATH before running kast."
    return
  fi

  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"

  if ! grep -Fq "$PATH_MARKER" "$rc_file"; then
    cat >>"$rc_file" <<EOF

$PATH_MARKER
export PATH="$bin_dir:\$PATH"
EOF
    log_success "Added ${bin_dir} to PATH in ${rc_file}"
    return
  fi

  log_step "PATH already includes the Kast installer block in ${rc_file}"
}

resolve_completion_mode() {
  case "${KAST_INSTALL_COMPLETIONS:-prompt}" in
    "" | prompt | auto)
      printf '%s\n' "prompt"
      ;;
    true | yes | 1)
      printf '%s\n' "enable"
      ;;
    false | no | 0)
      printf '%s\n' "disable"
      ;;
    *)
      die "KAST_INSTALL_COMPLETIONS must be one of: prompt, true, false"
      ;;
  esac
}

can_prompt() {
  [[ -r /dev/tty && -w /dev/tty ]]
}

prompt_yes_no() {
  local message="$1"
  local default_answer="${2:-yes}"
  local prompt_suffix="[Y/n]"
  local reply=""

  if [[ "$default_answer" == "no" ]]; then
    prompt_suffix="[y/N]"
  fi

  while true; do
    log_prompt "${message} ${prompt_suffix} "
    if ! IFS= read -r reply </dev/tty; then
      printf '\n' >/dev/tty
      return 1
    fi
    printf '\n' >/dev/tty

    case "$reply" in
      "")
        [[ "$default_answer" == "yes" ]]
        return
        ;;
      [Yy] | [Yy][Ee][Ss])
        return 0
        ;;
      [Nn] | [Nn][Oo])
        return 1
        ;;
    esac
  done
}

install_shell_completion() {
  local release_dir="$1"
  local install_root="$2"
  local shell_name="$3"

  if [[ -z "$shell_name" ]]; then
    log_note "Shell completion setup is available for Bash and Zsh. Run \`kast help completion\` for manual instructions."
    return
  fi

  local completion_dir="${release_dir}/completions"
  local completion_file="${completion_dir}/kast.${shell_name}"
  local completion_stderr="${tmp_dir}/completion-${shell_name}.stderr"
  local rc_file
  rc_file="$(resolve_shell_rc_file)"

  mkdir -p "$completion_dir"
  if ! "${release_dir}/kast" completion "$shell_name" >"$completion_file" 2>"$completion_stderr"; then
    rm -f "$completion_file" "$completion_stderr"
    log_note "This Kast build does not expose \`completion ${shell_name}\` yet, so the installer skipped shell completion setup."
    return
  fi
  rm -f "$completion_stderr"
  log_success "Generated ${shell_name} completion script at ${completion_file}"

  if [[ -z "$rc_file" ]]; then
    log_note "Open a shell init file and source ${install_root}/current/completions/kast.${shell_name} to enable completions."
    return
  fi

  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"

  if grep -Fq "$COMPLETION_START_MARKER" "$rc_file"; then
    log_step "Shell completion is already configured in ${rc_file}"
    return
  fi

  local completion_mode
  completion_mode="$(resolve_completion_mode)"

  if [[ "$completion_mode" == "disable" ]]; then
    log_note "Skipped shell completion setup. You can enable it later from ${install_root}/current/completions/kast.${shell_name}."
    return
  fi

  if [[ "$completion_mode" == "prompt" ]]; then
    if ! can_prompt; then
      log_note "Skipped interactive completion setup because no terminal prompt is available."
      log_note "To enable it later, source ${install_root}/current/completions/kast.${shell_name} from ${rc_file}."
      return
    fi
    if ! prompt_yes_no "Enable ${shell_name} completions in ${rc_file}?" "yes"; then
      log_note "Skipped shell completion setup. You can enable it later from ${install_root}/current/completions/kast.${shell_name}."
      return
    fi
  fi

  cat >>"$rc_file" <<EOF

$COMPLETION_START_MARKER
if [[ -r "${install_root}/current/completions/kast.${shell_name}" ]]; then
  source "${install_root}/current/completions/kast.${shell_name}"
fi
$COMPLETION_END_MARKER
EOF
  log_success "Enabled ${shell_name} completions in ${rc_file}"
}

main() {
  log_section "Kast installer"
  log "Install the published CLI, wire your shell, and leave the workspace commands ready to run."

  need_tool curl
  need_tool python3

  local java_bin
  java_bin="$(resolve_java_bin)"
  assert_java_21 "$java_bin"

  local release_repo
  local platform_id
  local install_root
  local bin_dir
  local archive_path
  local archive_name
  local archive_source
  local release_tag
  local archive_digest
  local shell_name

  release_repo="$(resolve_release_repo)"
  platform_id="$(detect_platform_id)"
  install_root="${KAST_INSTALL_ROOT:-${HOME}/.local/share/kast}"
  bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"
  shell_name="$(resolve_shell_name)"

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"

  if [[ -n "${KAST_ARCHIVE_PATH:-}" ]]; then
    log_section "Resolve release"
    archive_path="$KAST_ARCHIVE_PATH"
    [[ -f "$archive_path" ]] || die "KAST_ARCHIVE_PATH does not exist: $archive_path"
    archive_name="$(basename -- "$archive_path")"
    archive_source="$archive_path"
    release_tag="${KAST_VERSION:-local}"
    archive_digest="${KAST_EXPECTED_SHA256:-}"
    log_step "Using local archive ${archive_name}"
  else
    local metadata_url="${KAST_RELEASE_METADATA_URL:-}"
    if [[ -z "$metadata_url" ]]; then
      if [[ -n "${KAST_VERSION:-}" ]]; then
        metadata_url="https://api.github.com/repos/${release_repo}/releases/tags/${KAST_VERSION}"
      else
        metadata_url="https://api.github.com/repos/${release_repo}/releases/latest"
      fi
    fi

    local metadata_path="${tmp_dir}/release.json"
    log_section "Resolve release"
    log_step "Resolving release metadata for ${release_repo} (${platform_id})"
    curl \
      --fail \
      --location \
      --retry 3 \
      --retry-delay 2 \
      --silent \
      --show-error \
      --header "$GITHUB_API_ACCEPT" \
      --header "$GITHUB_API_VERSION" \
      --output "$metadata_path" \
      "$metadata_url"

    local release_info_path="${tmp_dir}/release-info.txt"
    local release_line=""

    extract_release_metadata "$metadata_path" "$platform_id" >"$release_info_path"

    local release_info=()
    while IFS= read -r release_line || [[ -n "$release_line" ]]; do
      release_info+=("$release_line")
    done <"$release_info_path"
    [[ "${#release_info[@]}" -eq 4 ]] || die "Release metadata parsing returned incomplete asset information"

    release_tag="${release_info[0]}"
    archive_name="${release_info[1]}"
    archive_source="${release_info[2]}"
    archive_digest="${release_info[3]}"
    archive_path="${tmp_dir}/${archive_name}"

    log_step "Downloading ${archive_name}"
    download_file "$archive_source" "$archive_path"
  fi

  log_section "Verify package"
  if [[ -n "$archive_digest" ]]; then
    local expected_sha256="${archive_digest#sha256:}"
    local actual_sha256
    actual_sha256="$(compute_sha256 "$archive_path")"
    [[ "$actual_sha256" == "$expected_sha256" ]] || die "Checksum verification failed for ${archive_name}"
    log_success "Verified SHA-256 for ${archive_name}"
  else
    log_note "No published SHA-256 digest was available for ${archive_name}; skipping checksum verification."
  fi

  local staging_dir="${tmp_dir}/extract"
  local release_dir="${install_root}/releases/${release_tag}/${platform_id}"
  local current_link="${install_root}/current"
  local bin_link="${bin_dir}/kast"
  local skill_bin_link="${bin_dir}/kast-skilled"

  log_section "Install files"
  extract_zip_archive "$archive_path" "$staging_dir"
  [[ -d "${staging_dir}/kast" ]] || die "Archive ${archive_name} did not contain the expected kast/ directory"

  rm -rf "$release_dir"
  mkdir -p "$(dirname -- "$release_dir")"
  mv "${staging_dir}/kast" "$release_dir"

  [[ -f "${release_dir}/kast" ]] || die "Installed archive did not contain the kast launcher"
  [[ -f "${release_dir}/bin/kast" ]] || die "Installed archive did not contain the kast native binary"
  [[ -f "${release_dir}/scripts/install-kast-skilled.sh" ]] || die "Installed archive did not contain the kast skill installer"
  [[ -d "${release_dir}/share/skills/kast" ]] || die "Installed archive did not contain the packaged kast skill"

  chmod +x "${release_dir}/kast" "${release_dir}/bin/kast" "${release_dir}/scripts/install-kast-skilled.sh"
  write_install_metadata \
    "${release_dir}/.install-metadata.json" \
    "$release_repo" \
    "$release_tag" \
    "$platform_id" \
    "$archive_name" \
    "$archive_source"

  mkdir -p "$install_root" "$bin_dir"
  ln -sfn "$release_dir" "$current_link"
  cat >"$bin_link" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec "${install_root}/current/kast" "\$@"
EOF
  chmod +x "$bin_link"

  cat >"$skill_bin_link" <<EOF
#!/usr/bin/env bash
set -euo pipefail
export KAST_SKILL_PATH="\${KAST_SKILL_PATH:-${install_root}/current/share/skills/kast}"
exec "${install_root}/current/scripts/install-kast-skilled.sh" "\$@"
EOF
  chmod +x "$skill_bin_link"
  log_success "Installed ${archive_name} into ${release_dir}"

  log_section "Shell setup"
  ensure_bin_dir_on_path "$bin_dir"
  install_shell_completion "$release_dir" "$install_root" "$shell_name"

  log_section "Ready"
  log_success "Launcher path: ${bin_link}"
  log_success "Skill launcher path: ${skill_bin_link}"
  if path_contains "$bin_dir"; then
    log_step "Try: kast --help"
    log_step "Then run: kast-skilled"
    log_step "Then: kast workspace ensure --workspace-root=/absolute/path/to/workspace"
  else
    log_note "Export PATH=\"${bin_dir}:\$PATH\""
    log_note "Then run: kast --help"
    log_note "Then run: kast-skilled"
    log_note "Then run: kast workspace ensure --workspace-root=/absolute/path/to/workspace"
  fi
}

main "$@"
