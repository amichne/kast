#!/usr/bin/env bash

supports_color() {
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then
    return 0
  fi
  if [[ -n "${NO_COLOR:-}" ]]; then
    return 1
  fi
  if [[ ! -t 2 ]]; then
    return 1
  fi
  [[ "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"
  shift

  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
    return
  fi

  printf '%s' "$*"
}

log_line() {
  local label="$1"
  local message="$2"
  printf '%s %s\n' "$label" "$message" >&2
}

log() {
  log_line "$(colorize '2' '│')" "$*"
}

log_section() {
  printf '\n%s\n' "$(colorize '1;36' "$*")" >&2
}

log_step() {
  log_line "$(colorize '1;34' '›')" "$*"
}

log_success() {
  log_line "$(colorize '1;32' '✓')" "$*"
}

log_note() {
  log_line "$(colorize '33' '•')" "$*"
}

log_prompt() {
  printf '%s %s' "$(colorize '1;34' '?')" "$*" >/dev/tty
}

die() {
  log_line "$(colorize '1;31' '✕')" "$*"
  exit 1
}

can_prompt() {
  [[ -r /dev/tty && -w /dev/tty ]]
}

prompt_yes_no() {
  local message="$1"
  local default_answer="${2:-no}"
  local prompt_suffix="[y/N]"
  local reply=""

  if [[ "$default_answer" == "yes" ]]; then
    prompt_suffix="[Y/n]"
  fi

  while true; do
    log_prompt "${message} ${prompt_suffix} "
    if ! IFS= read -r reply </dev/tty; then
      printf '\n' >/dev/tty
      return 1
    fi
    printf '\n' >/dev/tty

    case "$reply" in
      "")
        [[ "$default_answer" == "yes" ]]
        return
        ;;
      [Yy] | [Yy][Ee][Ss])
        return 0
        ;;
      [Nn] | [Nn][Oo])
        return 1
        ;;
    esac
  done
}

resolve_java_bin() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    local candidate="${JAVA_HOME}/bin/java"
    [[ -x "$candidate" ]] || die "JAVA_HOME is set but does not contain an executable java binary"
    printf '%s\n' "$candidate"
    return
  fi

  command -v java >/dev/null 2>&1 || die "Java 21 is required. Install Java 21 and rerun."
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
    resolved_output = output_dir.resolve()
    for member in archive.namelist():
        dest = (output_dir / member).resolve()
        if not str(dest).startswith(str(resolved_output) + "/"):
            raise Exception(f"Zip-slip attempt detected: {member}")
    archive.extractall(output_dir)
PY
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

  die "Neither sha256sum nor shasum is available for checksum computation"
}
