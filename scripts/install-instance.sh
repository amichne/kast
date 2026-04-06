#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib.sh"

resolve_repo_root() {
  cd -- "${SCRIPT_DIR}/.." && pwd
}

resolve_default_archive() {
  local repo_root="$1"
  local candidate
  local newest=""

  shopt -s nullglob
  for candidate in "${repo_root}"/kast/build/distributions/kast-*-portable.zip; do
    newest="$candidate"
  done
  shopt -u nullglob

  [[ -n "$newest" ]] || die "No portable zip found under ${repo_root}/kast/build/distributions. Run ./build.sh or pass --archive."
  printf '%s\n' "$newest"
}

generate_default_instance_name() {
  local adjectives=(
    agile
    amber
    brisk
    cedar
    clever
    copper
    coral
    dapper
    ember
    granite
    juniper
    nimble
    quiet
    silver
    spruce
    steady
    swift
    vivid
  )
  local animals=(
    badger
    falcon
    fox
    gecko
    heron
    kestrel
    lynx
    marten
    otter
    owl
    raven
    stoat
    swift
    tiger
    weasel
    wolf
    wren
    yak
  )
  local colors=(
    ash
    blue
    bronze
    cedar
    cloud
    copper
    crimson
    ember
    frost
    gold
    jade
    linen
    matte
    moss
    red
    shale
    silver
    velvet
  )
  local suffixes=(
    solo
    dupe
    trio
    quadra
    penta
    hexa
  )
  local adjective
  local animal
  local color
  local suffix

  adjective="${adjectives[RANDOM % ${#adjectives[@]}]}"
  animal="${animals[RANDOM % ${#animals[@]}]}"
  color="${colors[RANDOM % ${#colors[@]}]}"
  suffix="${suffixes[RANDOM % ${#suffixes[@]}]}"

  printf '%s\n' "${adjective}-${animal}"
  printf '%s\n' "${color}-${adjective}-${animal}"
  printf '%s\n' "${color}-${adjective}-${animal}-${suffix}"
}

generate_unique_instance_name() {
  local instances_root="$1"
  local bin_dir="$2"
  local candidate

  while IFS= read -r candidate; do
    if [[ ! -e "${instances_root}/${candidate}" && ! -e "${bin_dir}/kast-${candidate}" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done < <(generate_default_instance_name)

  die "Could not generate a unique instance name from the fallback sequence; pass --instance explicitly"
}

usage() {
  cat <<'USAGE' >&2
Usage: scripts/install-instance.sh [--instance <name>] [--archive <path-to-portable-zip>]

Installs a local/dev kast instance under:
  ~/.local/share/kast/instances/<name>

Creates launcher:
  ~/.local/bin/kast-<name>

If --instance is omitted, the script generates a default name like:
  agile-otter
USAGE
}

instance_name=""
archive_path=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --instance)
      [[ $# -ge 2 ]] || die "Missing value for --instance"
      instance_name="$2"
      shift 2
      ;;
    --archive)
      [[ $# -ge 2 ]] || die "Missing value for --archive"
      archive_path="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

instances_root="${HOME}/.local/share/kast/instances"
bin_dir="${HOME}/.local/bin"

if [[ -z "$instance_name" ]]; then
  instance_name="$(generate_unique_instance_name "$instances_root" "$bin_dir")"
  log "No --instance provided; using generated name '${instance_name}'"
fi

if [[ "$instance_name" =~ [^a-zA-Z0-9._-] ]]; then
  die "Instance name may contain only letters, digits, dot, underscore, and dash"
fi

repo_root="$(resolve_repo_root)"
if [[ -z "$archive_path" ]]; then
  archive_path="$(resolve_default_archive "$repo_root")"
fi

[[ -f "$archive_path" ]] || die "Archive not found: $archive_path"
command -v python3 >/dev/null 2>&1 || die "Missing required tool: python3"

java_bin="$(resolve_java_bin)"
assert_java_21 "$java_bin"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-instance-install.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

staging_dir="${tmp_dir}/extract"
extract_zip_archive "$archive_path" "$staging_dir"
[[ -d "${staging_dir}/kast" ]] || die "Archive ${archive_path} did not contain the expected kast/ directory"

instance_root="${instances_root}/${instance_name}"
launcher_path="${bin_dir}/kast-${instance_name}"

rm -rf "$instance_root"
mkdir -p "$(dirname -- "$instance_root")" "$bin_dir"
mv "${staging_dir}/kast" "$instance_root"

[[ -f "${instance_root}/kast" ]] || die "Installed archive did not contain the kast launcher"
[[ -f "${instance_root}/bin/kast" ]] || die "Installed archive did not contain the kast native binary"
chmod +x "${instance_root}/kast" "${instance_root}/bin/kast"

cat >"$launcher_path" <<EOF2
#!/usr/bin/env bash
set -euo pipefail
exec "${instance_root}/kast" "\$@"
EOF2
chmod +x "$launcher_path"

log "Installed local/dev instance '${instance_name}'"
log "Instance root: ${instance_root}"
log "Launcher: ${launcher_path}"
log "Run: ${launcher_path} --help"
