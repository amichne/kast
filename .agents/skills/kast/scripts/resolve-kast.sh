#!/bin/sh
# Resolve the kast CLI binary with a discovery cascade.
# Prints the absolute path to stdout and exits 0 on success.
# Prints diagnostics to stderr and exits 1 on failure.
set -eu

# Determine the project root: the directory containing this script's skill,
# which lives at .agents/skills/kast/scripts/resolve-kast.sh relative to root.
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/../../../.." && pwd)"

# 1. PATH — preferred if already installed
if command -v kast >/dev/null 2>&1; then
    command -v kast
    exit 0
fi

# 2. Local Gradle build output (./gradlew :kast:writeWrapperScript or make cli)
GRADLE_SCRIPT="${PROJECT_ROOT}/kast/build/scripts/kast"
if [ -x "${GRADLE_SCRIPT}" ]; then
    printf '%s\n' "${GRADLE_SCRIPT}"
    exit 0
fi

# 3. make cli output
DIST_SCRIPT="${PROJECT_ROOT}/dist/kast/kast"
if [ -x "${DIST_SCRIPT}" ]; then
    printf '%s\n' "${DIST_SCRIPT}"
    exit 0
fi

# 4. Auto-build fallback: requires Java 21+ and gradlew
if [ -x "${PROJECT_ROOT}/gradlew" ]; then
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
            (cd "${PROJECT_ROOT}" && ./gradlew :kast:writeWrapperScript --quiet --no-configuration-cache 2>&1) >&2 || true
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
printf '  1. PATH\n' >&2
printf '  2. %s\n' "${GRADLE_SCRIPT}" >&2
printf '  3. %s\n' "${DIST_SCRIPT}" >&2
printf '  4. Auto-build via ./gradlew :kast:writeWrapperScript\n' >&2
printf '\n' >&2
printf 'Install options:\n' >&2
printf '  ./install.sh                              # install from GitHub release\n' >&2
printf '  make cli                                  # build dist/kast/kast locally\n' >&2
printf '  ./gradlew :kast:writeWrapperScript        # build kast/build/scripts/kast\n' >&2
exit 1
