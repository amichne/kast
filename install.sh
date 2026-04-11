#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/scripts/lib.sh"

readonly SCRIPT_DIR
readonly DEFAULT_RELEASE_REPO="amichne/kast"
readonly GITHUB_API_ACCEPT="Accept: application/vnd.github+json"
readonly GITHUB_API_VERSION="X-GitHub-Api-Version: 2022-11-28"
readonly PATH_MARKER="# Added by the Kast installer"
readonly COMPLETION_START_MARKER="# >>> Kast completion >>>"
readonly COMPLETION_END_MARKER="# <<< Kast completion <<<"

tmp_dir=""

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

install_intellij_plugin() {
  local release_repo="$1"
  local release_tag="$2"
  local install_root="$3"

  log_section "Install IntelliJ plugin"

  local plugin_dir="${install_root}/plugins"
  local plugin_name="kast-intellij-${release_tag}.zip"
  local plugin_path="${plugin_dir}/${plugin_name}"
  local plugin_url="https://github.com/${release_repo}/releases/download/${release_tag}/${plugin_name}"

  mkdir -p "$plugin_dir"

  log_step "Downloading IntelliJ plugin ${plugin_name}"
  local download_attempt
  for download_attempt in 1 2 3; do
    if download_file "$plugin_url" "$plugin_path"; then
      break
    fi
    if [[ "$download_attempt" -eq 3 ]]; then
      log_note "Failed to download IntelliJ plugin after 3 attempts; skipping"
      return 1
    fi
    log_note "Download attempt ${download_attempt} failed; retrying in 5 seconds"
    sleep 5
  done

  log_success "IntelliJ plugin saved to ${plugin_path}"
  log_note "Install from IntelliJ: Settings → Plugins → ⚙️ → Install Plugin from Disk"
  log_note "Select: ${plugin_path}"
  return 0
}

prompt_components() {
  if ! can_prompt; then
    printf '%s\n' "standalone"
    return
  fi

  log_prompt "Which components? [standalone/intellij/all] (standalone) "
  local reply=""
  if ! IFS= read -r reply </dev/tty; then
    printf '\n' >/dev/tty
    printf '%s\n' "standalone"
    return
  fi
  printf '\n' >/dev/tty

  case "${reply,,}" in
    "" | standalone)
      printf '%s\n' "standalone"
      ;;
    intellij)
      printf '%s\n' "intellij"
      ;;
    all)
      printf '%s\n' "standalone,intellij"
      ;;
    *)
      printf '%s\n' "$reply"
      ;;
  esac
}

print_config_summary() {
  local install_root="$1"
  local bin_dir="$2"
  local jvm_only_mode="$3"
  local components="$4"
  local rc_file="$5"

  log_section "Install summary"
  log "Install root:   ${install_root}"
  log "Binary path:    ${bin_dir}/kast"
  log "Config dir:     ${install_root}/current"
  log "JVM-only mode:  ${jvm_only_mode}"
  log "Components:     ${components}"
  if [[ -n "$rc_file" ]]; then
    log "Shell RC:       ${rc_file}"
  fi
}

main() {
  local components=""
  local jvm_only="false"
  local non_interactive="false"

  # Parse CLI arguments (supports one-liner: -- --components=all --jvm-only)
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --components=*)
        components="${1#--components=}"
        shift
        ;;
      --components)
        [[ $# -ge 2 ]] || die "Missing value for --components"
        components="$2"
        shift 2
        ;;
      --jvm-only)
        jvm_only="true"
        shift
        ;;
      --non-interactive)
        non_interactive="true"
        shift
        ;;
      --help|-h)
        cat <<'USAGE' >&2
Usage: ./install.sh [options]

Install the Kast CLI and optional components.

Options:
  --components=<list>   Comma-separated: standalone,intellij,all (default: standalone)
  --jvm-only            Install JVM-only variant (no native binary)
  --non-interactive     Skip all interactive prompts
  --help, -h            Show this help
USAGE
        exit 0
        ;;
      *)
        die "Unknown argument: $1"
        ;;
    esac
  done

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
  if [[ "$jvm_only" == "true" ]]; then
    platform_id="${platform_id}-jvm"
  fi
  install_root="${KAST_INSTALL_ROOT:-${HOME}/.local/share/kast}"
  bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"
  shell_name="$(resolve_shell_name)"

  # Resolve components
  if [[ -z "$components" ]]; then
    if [[ "$non_interactive" == "true" ]]; then
      components="standalone"
    else
      components="$(prompt_components)"
    fi
  fi
  [[ "$components" == "all" ]] && components="standalone,intellij"

  local install_standalone="false"
  local install_intellij="false"
  IFS=',' read -r -a component_list <<<"$components"
  for comp in "${component_list[@]}"; do
    case "$comp" in
      standalone) install_standalone="true" ;;
      intellij)   install_intellij="true" ;;
      server)     install_standalone="true" ;;
      *)          die "Unknown component: $comp" ;;
    esac
  done

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"

  if [[ "$install_standalone" == "true" ]]; then
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
      local download_attempt
      for download_attempt in 1 2 3; do
        if download_file "$archive_source" "$archive_path"; then
          break
        fi
        if [[ "$download_attempt" -eq 3 ]]; then
          die "Failed to download ${archive_name} after 3 attempts"
        fi
        log_note "Download attempt ${download_attempt} failed; retrying in 5 seconds"
        sleep 5
      done
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

    log_section "Install files"

    # Partial install recovery: remove release_dir if it lacks metadata
    if [[ -d "$release_dir" && ! -f "${release_dir}/.install-metadata.json" ]]; then
      log_note "Removing partial install at ${release_dir}"
      rm -rf "$release_dir"
    fi

    # Broken symlink repair
    if [[ -L "$current_link" && ! -e "$current_link" ]]; then
      log_note "Removing broken symlink at ${current_link}"
      rm -f "$current_link"
    fi

    extract_zip_archive "$archive_path" "$staging_dir"
    [[ -d "${staging_dir}/kast" ]] || die "Archive ${archive_name} did not contain the expected kast/ directory"

    rm -rf "$release_dir"
    mkdir -p "$(dirname -- "$release_dir")"
    mv "${staging_dir}/kast" "$release_dir"

    [[ -f "${release_dir}/kast" ]] || die "Installed archive did not contain the kast launcher"

    if [[ "$jvm_only" != "true" ]]; then
      [[ -f "${release_dir}/bin/kast" ]] || die "Installed archive did not contain the kast native binary"
      chmod +x "${release_dir}/kast" "${release_dir}/bin/kast"
    else
      chmod +x "${release_dir}/kast"
      if [[ -f "${release_dir}/bin/kast" ]]; then
        chmod +x "${release_dir}/bin/kast"
      fi
    fi

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
    log_success "Installed ${archive_name} into ${release_dir}"

    log_section "Shell setup"
    ensure_bin_dir_on_path "$bin_dir"
    install_shell_completion "$release_dir" "$install_root" "$shell_name"
  fi

  # Install IntelliJ plugin component
  if [[ "$install_intellij" == "true" ]]; then
    local resolved_tag="${release_tag:-}"
    if [[ -z "$resolved_tag" ]]; then
      # Need to resolve the release tag for plugin download
      resolved_tag="${KAST_VERSION:-}"
      if [[ -z "$resolved_tag" ]]; then
        local latest_meta="${tmp_dir}/latest-release.json"
        curl \
          --fail --location --retry 3 --retry-delay 2 --silent --show-error \
          --header "$GITHUB_API_ACCEPT" \
          --header "$GITHUB_API_VERSION" \
          --output "$latest_meta" \
          "https://api.github.com/repos/${release_repo}/releases/latest"
        resolved_tag="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['tag_name'])" "$latest_meta")"
      fi
    fi
    install_intellij_plugin "$release_repo" "$resolved_tag" "$install_root" || true
  fi

  local rc_file
  rc_file="$(resolve_shell_rc_file)"
  print_config_summary "$install_root" "$bin_dir" "$jvm_only" "$components" "$rc_file"

  log_section "Ready"
  if [[ "$install_standalone" == "true" ]]; then
    log_success "Launcher path: ${bin_dir}/kast"
    if path_contains "$bin_dir"; then
      log_step "Try: kast --help"
      log_step "If you use the packaged skill, run: kast install skill from your workspace root"
      log_step "Then: kast workspace ensure --workspace-root=/absolute/path/to/workspace"
    else
      log_note "Export PATH=\"${bin_dir}:\$PATH\""
      log_note "Then run: kast --help"
      log_note "If you use the packaged skill, run: kast install skill from your workspace root"
      log_note "Then run: kast workspace ensure --workspace-root=/absolute/path/to/workspace"
    fi
  fi
  if [[ "$install_intellij" == "true" ]]; then
    log_step "IntelliJ plugin: ${install_root}/plugins/"
  fi
}

main "$@"
