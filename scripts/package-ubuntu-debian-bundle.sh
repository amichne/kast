#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/package-ubuntu-debian-bundle.sh --cli-archive <zip> --backend-archive <zip> --version <tag> [--output <tar.gz>]

Compatibility wrapper around the Rust packager:
  kast package ubuntu-debian-bundle ...
USAGE
}

repo_root="$(resolve_repo_root)"
forward_args=()

case "${1:-}" in
  --help|-h|help)
    usage
    exit 0
    ;;
esac

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --output)
      [[ "$#" -ge 2 ]] || die "--output requires a tar.gz path"
      forward_args+=(--bundle-output "$2")
      shift 2
      ;;
    --output=*)
      forward_args+=("--bundle-output=${1#--output=}")
      shift
      ;;
    *)
      forward_args+=("$1")
      shift
      ;;
  esac
done

if [[ -n "${KAST_PACKAGE_KAST_BIN:-}" ]]; then
  [[ -x "$KAST_PACKAGE_KAST_BIN" ]] || die "KAST_PACKAGE_KAST_BIN is not executable: $KAST_PACKAGE_KAST_BIN"
  command_args=("$KAST_PACKAGE_KAST_BIN")
else
  command -v cargo >/dev/null 2>&1 || die "Missing cargo and no KAST_PACKAGE_KAST_BIN override provided"
  command_args=(cargo run --manifest-path "${repo_root}/cli-rs/Cargo.toml" --locked --)
fi

exec "${command_args[@]}" \
  package ubuntu-debian-bundle \
  --repo-root "$repo_root" \
  "${forward_args[@]}"
