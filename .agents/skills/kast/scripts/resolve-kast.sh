#!/usr/bin/env bash
# Resolve the kast CLI binary with a discovery cascade.
# Prints the absolute path to stdout and exits 0 on success.
# Prints diagnostics to stderr and exits 1 on failure.
set -euo pipefail

# Determine the project root: the directory containing this skill's skill,
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null || echo "${SCRIPT_DIR}")"

# Use the explicit source root when provided, otherwise fall back to the
# repository that contains this skill.
SOURCE_ROOT="${KAST_SOURCE_ROOT:-${PROJECT_ROOT}}"
GRADLE_SCRIPT="${SOURCE_ROOT}/kast-cli/build/scripts/kast-cli"
DIST_SCRIPT="${SOURCE_ROOT}/dist/cli/kast-cli"

# 1. Explicit override — KAST_CLI_PATH takes precedence over everything
if [ -n "${KAST_CLI_PATH:-}" ] && [ -x "${KAST_CLI_PATH}" ]; then
    printf '%s\n' "${KAST_CLI_PATH}"
    exit 0
fi

# 2. PATH — use the installed binary if available
if command -v kast >/dev/null 2>&1; then
    command -v kast
    exit 0
fi

# 3. Check for locally built versions from the explicit source root or the
# repo that contains this skill. This supports source iteration without a full
# install. Check the Gradle wrapper output first, then the portable dist layout.
if [ -x "${GRADLE_SCRIPT}" ]; then
    printf '%s\n' "${GRADLE_SCRIPT}"
    exit 0
fi

if [ -x "${DIST_SCRIPT}" ]; then
    printf '%s\n' "${DIST_SCRIPT}"
    exit 0
fi

# 5. Auto-build fallback: requires Java 21+ and gradlew
if [ -x "${SOURCE_ROOT}/gradlew" ]; then
    # Check for Java 21+
    JAVA_BIN=""
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        JAVA_BIN="${JAVA_HOME}/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_BIN="$(command -v java)"
    fi

    if [ -n "${JAVA_BIN}" ]; then
        SPEC_VERSION="$("${JAVA_BIN}" -XshowSettings:properties -version 2>&1 \
            | awk -F'= ' '/java.specification.version =/ { print $2; exit }')"
        MAJOR="${SPEC_VERSION%%.*}"
        if [ -n "${MAJOR}" ] && [ "${MAJOR}" -ge 21 ] 2>/dev/null; then
            printf 'kast not found; building from source (this may take a minute)...\n' >&2
            (cd "${SOURCE_ROOT}" && ./gradlew :kast-cli:writeWrapperScript --quiet 2>&1) >&2 || true
            if [ -x "${GRADLE_SCRIPT}" ]; then
                printf '%s\n' "${GRADLE_SCRIPT}"
                exit 0
            fi
            printf 'Gradle build completed but %s was not produced.\n' "${GRADLE_SCRIPT}" >&2
        else
            printf 'Java 21 or newer is required to build kast (found spec version: %s).\n' "${SPEC_VERSION:-unknown}" >&2
        fi
    else
        printf 'Java not found; cannot build kast from source.\n' >&2
    fi
fi

printf 'kast CLI not found. Tried:\n' >&2
printf '  1. KAST_CLI_PATH env var\n' >&2
printf '  2. PATH\n' >&2
printf '  3. %s\n' "${GRADLE_SCRIPT}" >&2
printf '  4. %s\n' "${DIST_SCRIPT}" >&2
printf '  5. Auto-build via ./gradlew :kast-cli:writeWrapperScript\n' >&2
printf '\n' >&2
printf 'Install options:\n' >&2
printf '  ./install.sh                                     # install from GitHub release\n' >&2
printf '  ./build.sh cli                                   # build dist/cli/kast-cli locally\n' >&2
printf '  ./gradlew :kast-cli:writeWrapperScript           # build kast-cli/build/scripts/kast-cli\n' >&2
exit 1
