#!/usr/bin/env bash
set -euo pipefail
umask 022
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_dir/activate-codex-developer.sh"
base="$KAST_CLOUD_ROOT"
source_root="$base/release-checkout"
revision=cf067e5fcdbc0de5db077e101d50f0c287edd4fd
tag=developer-v0.0.4483
test "$(uname -sm)" = 'Linux x86_64'
mkdir -p "$base"
if [[ ! -e "$source_root" ]]; then
  git clone --branch "$tag" --depth 1 https://github.com/amichne/kast.git "$source_root"
fi
test "$(git -C "$source_root" rev-parse HEAD)" = "$revision"
test -z "$(git -C "$source_root" status --porcelain --untracked-files=normal)"

mise_bin="${MISE_BIN:-}"
if [[ -z "$mise_bin" ]]; then
  mise_bin="$(command -v mise || true)"
  mise_bin="${mise_bin:-/workspace/.cloud/kast-051/tools/mise/bin/mise}"
fi
(cd "$source_root" && "$mise_bin" install java node)

mkdir -p "$base/home" "$base/gradle" "$base/bin"
cat > "$base/bin/kast" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
unset KAST_INSTALL_ROOT XDG_DATA_HOME XDG_CONFIG_HOME XDG_CACHE_HOME XDG_STATE_HOME
export HOME="$base/home"
export GRADLE_USER_HOME="$base/gradle"
exec "$HOME/.local/bin/kast" "$@"
WRAPPER
chmod +x "$base/bin/kast"
if [[ ! -x "$base/home/.local/bin/kast" ]]; then
  (
    mkdir -p "$base/artifacts/payload"
    cd "$base/artifacts"
    temporary=
    trap 'if [[ -n "$temporary" ]]; then rm -f -- "$temporary"; fi' EXIT
    while read -r digest path; do
      if printf '%s  %s\n' "$digest" "$path" | sha256sum --check --status; then
        continue
      fi
      temporary="$(mktemp "${path}.XXXXXX")"
      curl --fail --silent --show-error --location --retry 3 \
        --proto '=https' --proto-redir '=https' \
        --output "$temporary" "https://github.com/amichne/kast/releases/download/$tag/${path##*/}"
      printf '%s  %s\n' "$digest" "$temporary" | sha256sum --check --strict >&2
      mv -- "$temporary" "$path"
      temporary=
    done < "$script_dir/codex-developer.SHA256SUMS"
    sha256sum --check --strict "$script_dir/codex-developer.SHA256SUMS" >&2
    unset KAST_INSTALL_ROOT XDG_DATA_HOME XDG_CONFIG_HOME XDG_CACHE_HOME XDG_STATE_HOME
    export HOME="$base/home"
    bash "$source_root/install.sh" --local-artifact "$base/artifacts" --json
  ) > "$base/install-result.json"
fi
"$base/bin/kast" status --json > "$base/installation-status.json"
jq -e '
  .product.state == "VERIFIED" and
  .product.value.releaseTag == "developer-v0.0.4483" and
  .product.value.version == "0.0.4483" and
  .product.value.publicVersion == null and
  .product.value.target == "linux-x86_64" and
  .product.value.runtimeArchiveSha256 == "5fedf8709c1cc541f425ccc7c18a11d44820b79f3944bb9a3cf8382b954440a7" and
  .product.value.sourceRevision == "cf067e5fcdbc0de5db077e101d50f0c287edd4fd" and
  .product.value.buildRecordSha256 == "53c9a9e41c38a2d2ac4526898e77b80f93887c483e2294b8e1f538bbddc9de35" and
  .product.value.bundleSha256 == "689a1495122de85c5e28ede77434fc470ed1b8b06b9bc63a9d3c132a4c124eca" and
  .product.value.qualificationSha256 == "c0c2401d9b16c1127e50356a32cb397ea7400d7a54dcbd0be4666fd07e2d5940" and
  .unresolvedTransaction.state == "VERIFIED" and
  .unresolvedTransaction.value.records == []
' "$base/installation-status.json" >/dev/null
test "$("$base/bin/kast" --version)" = 0.0.4483
