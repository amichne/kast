#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd
)"
readonly DEFAULT_RELEASE_REPO="amichne/kast"
readonly GITHUB_API_ACCEPT="Accept: application/vnd.github+json"
readonly GITHUB_API_VERSION="X-GitHub-Api-Version: 2022-11-28"

tmp_dir=""

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
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

  command -v java >/dev/null 2>&1 || die "Java 21 is required. Install Java 21 and rerun ./install.sh."
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

ensure_bin_dir_on_path() {
  local bin_dir="$1"

  if path_contains "$bin_dir"; then
    return
  fi

  if [[ "${KAST_SKIP_PATH_UPDATE:-false}" == "true" ]]; then
    log "Add ${bin_dir} to PATH before running kast."
    return
  fi

  local rc_file
  rc_file="$(resolve_shell_rc_file)"

  if [[ -z "$rc_file" ]]; then
    log "Add ${bin_dir} to PATH before running kast."
    return
  fi

  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"

  local marker="# Added by the Kast installer"
  if ! grep -Fq "$marker" "$rc_file"; then
    cat >>"$rc_file" <<EOF

$marker
export PATH="$bin_dir:\$PATH"
EOF
    log "Added ${bin_dir} to PATH in ${rc_file}"
  fi
}

main() {
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

  release_repo="$(resolve_release_repo)"
  platform_id="$(detect_platform_id)"
  install_root="${KAST_INSTALL_ROOT:-${HOME}/.local/share/kast}"
  bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"

  if [[ -n "${KAST_ARCHIVE_PATH:-}" ]]; then
    archive_path="$KAST_ARCHIVE_PATH"
    [[ -f "$archive_path" ]] || die "KAST_ARCHIVE_PATH does not exist: $archive_path"
    archive_name="$(basename -- "$archive_path")"
    archive_source="$archive_path"
    release_tag="${KAST_VERSION:-local}"
    archive_digest="${KAST_EXPECTED_SHA256:-}"
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
    log "Resolving release metadata for ${release_repo} (${platform_id})"
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

    mapfile -t release_info < <(extract_release_metadata "$metadata_path" "$platform_id")
    [[ "${#release_info[@]}" -eq 4 ]] || die "Release metadata parsing returned incomplete asset information"

    release_tag="${release_info[0]}"
    archive_name="${release_info[1]}"
    archive_source="${release_info[2]}"
    archive_digest="${release_info[3]}"
    archive_path="${tmp_dir}/${archive_name}"

    log "Downloading ${archive_name}"
    download_file "$archive_source" "$archive_path"
  fi

  if [[ -n "$archive_digest" ]]; then
    local expected_sha256="${archive_digest#sha256:}"
    local actual_sha256
    actual_sha256="$(compute_sha256 "$archive_path")"
    [[ "$actual_sha256" == "$expected_sha256" ]] || die "Checksum verification failed for ${archive_name}"
  else
    log "No published SHA-256 digest was available for ${archive_name}; skipping checksum verification."
  fi

  local staging_dir="${tmp_dir}/extract"
  local release_dir="${install_root}/releases/${release_tag}/${platform_id}"
  local current_link="${install_root}/current"
  local bin_link="${bin_dir}/kast"

  extract_zip_archive "$archive_path" "$staging_dir"
  [[ -d "${staging_dir}/kast" ]] || die "Archive ${archive_name} did not contain the expected kast/ directory"

  rm -rf "$release_dir"
  mkdir -p "$(dirname -- "$release_dir")"
  mv "${staging_dir}/kast" "$release_dir"

  [[ -f "${release_dir}/kast" ]] || die "Installed archive did not contain the kast launcher"
  [[ -f "${release_dir}/bin/kast-helper" ]] || die "Installed archive did not contain the kast helper binary"

  chmod +x "${release_dir}/kast" "${release_dir}/bin/kast-helper"
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
  ensure_bin_dir_on_path "$bin_dir"

  log "Installed ${archive_name} into ${release_dir}"
  log "Launcher path: ${bin_link}"
  if path_contains "$bin_dir"; then
    log "Next step: kast workspace ensure --workspace-root=/absolute/path/to/workspace"
  else
    log "Next step: export PATH=\"${bin_dir}:\$PATH\""
    log "Then run: kast workspace ensure --workspace-root=/absolute/path/to/workspace"
  fi
}

main "$@"
