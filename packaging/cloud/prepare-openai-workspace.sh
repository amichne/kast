#!/usr/bin/env bash
set -euo pipefail

setup_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$setup_directory/openai-kast.env"
[[ $# -eq 1 ]] || { echo 'Usage: bash prepare-openai-workspace.sh /absolute/repository' >&2; exit 2; }
uv run --offline --locked --project "$setup_directory/.." python "$setup_directory/openai-image.py" prepare \
  --workspace "$1" --sha256 "$bundle_sha256" --version "$bundle_version" --root "$image_root"
