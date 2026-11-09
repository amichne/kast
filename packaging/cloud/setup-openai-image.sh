#!/usr/bin/env bash
set -euo pipefail

setup_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$setup_directory/openai-kast.env"
[[ $# -eq 1 ]] || { echo 'Usage: bash setup-openai-image.sh /path/to/qualified-setup.tar.gz' >&2; exit 2; }
command -v uv >/dev/null
command -v codex >/dev/null

uv run --locked --project "$setup_directory/.." python "$setup_directory/openai-image.py" install \
  --archive "$1" --sha256 "$bundle_sha256" \
  --version "$bundle_version" --root "$image_root"

repository_root="$(git -C "$setup_directory" rev-parse --show-toplevel)"
profile_revision="$(git -C "$repository_root" rev-parse HEAD)"
profile_root="$image_root/profile"
if [[ -e "$profile_root" || -L "$profile_root" ]]; then
  [[ ! -L "$profile_root" && -f "$profile_root/source-revision" &&
    "$(cat "$profile_root/source-revision")" == "$profile_revision" ]] || {
    echo 'The existing image profile belongs to another setup revision; choose a fresh image root.' >&2
    exit 1
  }
else
  profile_stage="$(mktemp -d "$image_root/.profile-stage.XXXXXX")"
  git -C "$repository_root" archive HEAD packaging bin | tar -x -C "$profile_stage"
  printf '%s\n' "$profile_revision" > "$profile_stage/source-revision"
  mv -- "$profile_stage" "$profile_root"
fi
uv run --locked --project "$profile_root/packaging" python \
  "$profile_root/packaging/cloud/openai-image.py" verify \
  --sha256 "$bundle_sha256" --version "$bundle_version" --root "$image_root"

slopsentral_command="$HOME/.local/bin/slopsentral"
if [[ -x "$slopsentral_command" ]]; then
  "$slopsentral_command" self update --version "$SLOPSENTRAL_VERSION"
else
  stage="$(mktemp -d)"
  trap 'rm -rf -- "$stage"' EXIT
  release="https://github.com/amichne/slopsentral/releases/download/slopsentral-v$SLOPSENTRAL_VERSION"
  for asset in slopsentral.py slopsentral.py.lock kotlin-horizontalization-check kotlin-horizontalization-check.lock; do
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
      "$release/$asset" --output "$stage/$asset"
  done
  uv run --locked --script "$stage/slopsentral.py" self install --version "$SLOPSENTRAL_VERSION"
fi

"$slopsentral_command" install --host codex \
  --plugin software-engineering --plugin kotlin-engineering \
  --plugin intellij-plugin-development --plugin repository-knowledge --plugin developer-tooling

codex plugin list --json > "$image_root/codex-plugins.json"
printf 'Prepared Kast %s and Slopsentral %s for the image user.\n' "$bundle_version" "$SLOPSENTRAL_VERSION"
