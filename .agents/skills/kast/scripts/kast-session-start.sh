#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
KAST_BIN="$(bash "${SCRIPT_DIR}/resolve-kast.sh")"

# Version parity: compare the CLI version against the installed skill marker.
INSTALLED_VERSION=""
if [[ -f "${SKILL_DIR}/.kast-version" ]]; then
  INSTALLED_VERSION="$(<"${SKILL_DIR}/.kast-version")"
  INSTALLED_VERSION="${INSTALLED_VERSION%%[[:space:]]}"
fi

CLI_VERSION=""
if RAW=$("${KAST_BIN}" --version 2>/dev/null); then
  # Strip ANSI escapes, then extract version from "Kast CLI <version>"
  CLI_VERSION=$(printf '%s' "${RAW}" | sed 's/\x1b\[[0-9;]*m//g' | sed -n 's/^Kast CLI //p')
  CLI_VERSION="${CLI_VERSION%%[[:space:]]}"
fi

if [[ -n "${INSTALLED_VERSION}" && -n "${CLI_VERSION}" && "${INSTALLED_VERSION}" != "${CLI_VERSION}" ]]; then
  printf 'echo "KAST VERSION MISMATCH: CLI=%s, installed skill=%s. Run: kast install skill"\n' \
    "${CLI_VERSION}" "${INSTALLED_VERSION}" >&2
  exit 1
fi

printf 'export PATH=%q:"${PATH}"\n' "$(dirname -- "${KAST_BIN}")"
