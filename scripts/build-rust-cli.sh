#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/build-rust-cli.sh [--source-dir <path>] [--output <path>]

Build the Rust Kast CLI and copy the release binary to a stable path.

Options:
  --source-dir <path>   Path to the kast-rs checkout. Defaults to
                        KAST_RUST_CLI_DIR or ../kast-rs relative to this repo.
  --output <path>       Output binary path. Defaults to build/rust-cli/kast.
  --help, -h            Show this help.
USAGE
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

absolute_path() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path
print(Path(sys.argv[1]).expanduser().resolve())
PY
}

repo_root="$(resolve_repo_root)"
source_dir="${KAST_RUST_CLI_DIR:-${repo_root}/../kast-rs}"
output_path="${repo_root}/build/rust-cli/kast"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir)
      [[ $# -ge 2 ]] || die "Missing value for --source-dir"
      source_dir="$2"; shift 2 ;;
    --source-dir=*)
      source_dir="${1#--source-dir=}"; shift ;;
    --output)
      [[ $# -ge 2 ]] || die "Missing value for --output"
      output_path="$2"; shift 2 ;;
    --output=*)
      output_path="${1#--output=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

source_dir="$(absolute_path "$source_dir")"
output_path="$(absolute_path "$output_path")"

[[ -f "${source_dir}/Cargo.toml" ]] || die "kast-rs Cargo.toml not found at ${source_dir}"
command -v cargo >/dev/null 2>&1 || die "cargo is required to build the Rust Kast CLI"

(
  cd "$source_dir"
  cargo build --release
)

binary_path="${source_dir}/target/release/kast"
[[ -x "$binary_path" ]] || die "Rust CLI binary was not created at ${binary_path}"

mkdir -p "$(dirname -- "$output_path")"
cp "$binary_path" "$output_path"
chmod 755 "$output_path"
printf '%s\n' "$output_path"
