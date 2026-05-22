#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '%s\n' "$*" >&2
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

write_zip() {
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

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/package-headless-agent-bundle.sh --cli-archive <zip> --backend-archive <zip> [options]

Build a self-contained headless agent install bundle.

Required:
  --cli-archive <zip>       Rust kast CLI zip
  --backend-archive <zip>   Standalone backend portable zip

Options:
  --version <version>       Version label for manifest/install metadata
  --platform-id <id>        Platform identifier; currently linux-x64
  --output <zip>            Output bundle path
  --help, -h                Show this help
USAGE
}

repo_root="$(resolve_repo_root)"
cli_archive=""
backend_archive=""
version="${KAST_AGENT_VERSION:-agent}"
platform_id="linux-x64"
output_path=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cli-archive)
      [[ $# -ge 2 ]] || die "Missing value for --cli-archive"
      cli_archive="$2"; shift 2 ;;
    --cli-archive=*)
      cli_archive="${1#--cli-archive=}"; shift ;;
    --backend-archive)
      [[ $# -ge 2 ]] || die "Missing value for --backend-archive"
      backend_archive="$2"; shift 2 ;;
    --backend-archive=*)
      backend_archive="${1#--backend-archive=}"; shift ;;
    --version)
      [[ $# -ge 2 ]] || die "Missing value for --version"
      version="$2"; shift 2 ;;
    --version=*)
      version="${1#--version=}"; shift ;;
    --platform-id)
      [[ $# -ge 2 ]] || die "Missing value for --platform-id"
      platform_id="$2"; shift 2 ;;
    --platform-id=*)
      platform_id="${1#--platform-id=}"; shift ;;
    --output)
      [[ $# -ge 2 ]] || die "Missing value for --output"
      output_path="$2"; shift 2 ;;
    --output=*)
      output_path="${1#--output=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      die "Unknown argument: $1" ;;
  esac
done

need_tool python3

[[ "$platform_id" == "linux-x64" ]] || die "Headless agent bundles currently support linux-x64 only"
[[ -n "$cli_archive" ]] || die "--cli-archive is required"
[[ -n "$backend_archive" ]] || die "--backend-archive is required"
[[ -f "$cli_archive" ]] || die "CLI archive not found: $cli_archive"
[[ -f "$backend_archive" ]] || die "Backend archive not found: $backend_archive"
[[ -f "${repo_root}/kast.sh" ]] || die "Missing ${repo_root}/kast.sh"
[[ -f "${repo_root}/scripts/headless-agent-install.sh" ]] || die "Missing headless-agent installer"

if [[ -z "$output_path" ]]; then
  output_path="${repo_root}/dist/kast-headless-agent-${version}-${platform_id}.zip"
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-agent-bundle.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

staging_dir="${tmp_dir}/staging"
mkdir -p "${staging_dir}/artifacts" "${staging_dir}/scripts" "$(dirname -- "$output_path")"

cp "$cli_archive" "${staging_dir}/artifacts/kast.zip"
cp "$backend_archive" "${staging_dir}/artifacts/kast-standalone.zip"
cp "${repo_root}/kast.sh" "${staging_dir}/kast.sh"
cp "${repo_root}/scripts/headless-agent-install.sh" "${staging_dir}/scripts/headless-agent-install.sh"
chmod +x \
  "${staging_dir}/kast.sh" \
  "${staging_dir}/scripts/headless-agent-install.sh"

cli_sha="$(compute_sha256 "${staging_dir}/artifacts/kast.zip")"
backend_sha="$(compute_sha256 "${staging_dir}/artifacts/kast-standalone.zip")"

{
  printf '%s  %s\n' "$cli_sha" "artifacts/kast.zip"
  printf '%s  %s\n' "$backend_sha" "artifacts/kast-standalone.zip"
} > "${staging_dir}/checksums.txt"

cat > "${staging_dir}/install.sh" <<INSTALL
#!/usr/bin/env bash
set -euo pipefail

script_dir="\$(cd -- "\$(dirname -- "\${BASH_SOURCE[0]}")" && pwd)"

need_tool() {
  local tool_name="\$1"
  command -v "\$tool_name" >/dev/null 2>&1 || {
    printf 'error: missing required tool: %s\n' "\$tool_name" >&2
    exit 1
  }
}

file_uri() {
  python3 - "\$1" <<'PY'
import sys
from pathlib import Path

print(Path(sys.argv[1]).resolve().as_uri())
PY
}

checksum_for() {
  local relative_path="\$1"
  awk -v path="\$relative_path" '\$2 == path { print \$1; found = 1 } END { if (!found) exit 1 }' "\${script_dir}/checksums.txt"
}

need_tool git
need_tool python3
need_tool curl

export KAST_AGENT_CLI_URL="\$(file_uri "\${script_dir}/artifacts/kast.zip")"
export KAST_AGENT_BACKEND_URL="\$(file_uri "\${script_dir}/artifacts/kast-standalone.zip")"
export KAST_AGENT_CLI_SHA256="\$(checksum_for "artifacts/kast.zip")"
export KAST_AGENT_BACKEND_SHA256="\$(checksum_for "artifacts/kast-standalone.zip")"
export KAST_AGENT_VERSION="\${KAST_AGENT_VERSION:-${version}}"
export KAST_AGENT_WORKSPACE="\${KAST_AGENT_WORKSPACE:-\${GITHUB_WORKSPACE:-\$PWD}}"

"\${script_dir}/scripts/headless-agent-install.sh"
INSTALL
chmod +x "${staging_dir}/install.sh"

cat > "${staging_dir}/README.md" <<README
# Kast headless agent bundle

This bundle installs the Kast CLI, standalone backend, packaged skill, and
repo-local Copilot extension from bundle-local artifacts. It is intended for
Linux x64 headless agent images and CI-like bootstrap flows.

The installer verifies the contained CLI launcher, standalone runtime libs,
packaged skill, install manifest, Copilot hooks, native extension files, and
executable resolver before it exits.

## Contents

- \`install.sh\` - entrypoint for this bundle
- \`artifacts/kast.zip\` - Rust kast CLI archive
- \`artifacts/kast-standalone.zip\` - standalone backend portable archive
- \`checksums.txt\` - SHA-256 digests for bundled artifacts
- \`manifest.json\` - machine-readable bundle metadata
- \`kast.sh\` and \`scripts/headless-agent-install.sh\` - bundle-local installer logic

## Install

Run from the target Git workspace, or set \`KAST_AGENT_WORKSPACE\` explicitly:

\`\`\`bash
unzip kast-headless-agent-${version}-${platform_id}.zip -d kast-agent
cd /path/to/target/workspace
/path/to/kast-agent/install.sh
source "\${KAST_AGENT_INSTALL_ROOT:-\$HOME/.kast-agent}/kast-env.sh"
\`\`\`

Optional overrides:

- \`KAST_AGENT_INSTALL_ROOT\` - contained install root, defaults to \`\$HOME/.kast-agent\`
- \`KAST_AGENT_WORKSPACE\` - Git workspace for repo-local Copilot extension install
- \`KAST_AGENT_VERSION\` - install metadata label, defaults to \`${version}\`
- \`KAST_SKIP_COPILOT_EXTENSION\` - set \`true\` to skip Copilot extension install
README

python3 - "${staging_dir}/manifest.json" "$version" "$platform_id" "$cli_sha" "$backend_sha" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
version = sys.argv[2]
platform = sys.argv[3]
cli_sha = sys.argv[4]
backend_sha = sys.argv[5]
payload = {
    "schemaVersion": 1,
    "kind": "KAST_HEADLESS_AGENT_BUNDLE",
    "version": version,
    "platform": platform,
    "entrypoint": "install.sh",
    "artifacts": [
        {
            "role": "cli",
            "path": "artifacts/kast.zip",
            "sha256": cli_sha,
        },
        {
            "role": "standalone-backend",
            "path": "artifacts/kast-standalone.zip",
            "sha256": backend_sha,
        },
    ],
}
manifest_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

rm -f "$output_path"
write_zip "$output_path" "$staging_dir"
log "Wrote ${output_path}"
