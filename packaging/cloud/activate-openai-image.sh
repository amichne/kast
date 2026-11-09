#!/usr/bin/env bash
set -euo pipefail

setup_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$setup_directory/openai-kast.env"

# Emit nothing until the exact installed bundle and native version are verified.
kast_exports="$(uv run --offline --locked --project "$setup_directory/.." python "$setup_directory/openai-image.py" activate \
  --sha256 "$KAST_BUNDLE_SHA256" --version "$KAST_BUNDLE_VERSION" --root "$KAST_IMAGE_ROOT")"
printf '%s\n' 'export PATH="$HOME/.local/bin:$PATH"' "$kast_exports"
