#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GRADLE_PROPS="$PROJECT_DIR/gradle.properties"

if [[ ! -f "$GRADLE_PROPS" ]]; then
    echo "ERROR: gradle.properties not found at $GRADLE_PROPS" >&2
    exit 1
fi

BUMP_TYPE="none"
ADD_SNAPSHOT=0

usage() {
    cat <<USAGE
Usage: ./scripts/bump-version.sh [none|major|minor|patch] [--snapshot]

Examples:
  ./scripts/bump-version.sh none              # leave version unchanged
  ./scripts/bump-version.sh none --snapshot   # 1.2.3 -> 1.2.3-SNAPSHOT
  ./scripts/bump-version.sh patch             # 1.2.3 -> 1.2.4
  ./scripts/bump-version.sh patch --snapshot  # 1.2.3 -> 1.2.4-SNAPSHOT
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        none|major|minor|patch)
            BUMP_TYPE="$1"
            shift
            ;;
        --snapshot)
            ADD_SNAPSHOT=1
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument '$1'" >&2
            usage
            exit 1
            ;;
    esac
done

CURRENT_RAW_VERSION=$(grep -E '^(version|VERSION)=' "$GRADLE_PROPS" | head -n1 | cut -d'=' -f2-)
if [[ -z "$CURRENT_RAW_VERSION" ]]; then
    echo "ERROR: version is missing in gradle.properties" >&2
    exit 1
fi

BASE_VERSION="${CURRENT_RAW_VERSION%-SNAPSHOT}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE_VERSION"

if [[ -z "$MAJOR" || -z "$MINOR" || -z "$PATCH" ]]; then
    echo "ERROR: version must be semantic version format x.y.z or x.y.z-SNAPSHOT" >&2
    exit 1
fi

case "$BUMP_TYPE" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    patch)
        PATCH=$((PATCH + 1))
        ;;
    none)
        ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
if [[ "$ADD_SNAPSHOT" -eq 1 ]]; then
    NEW_VERSION="$NEW_VERSION-SNAPSHOT"
fi

if [[ "$NEW_VERSION" == "$CURRENT_RAW_VERSION" ]]; then
    echo "Version unchanged: $CURRENT_RAW_VERSION"
    exit 0
fi

replace_property() {
    local key="$1"
    local value="$2"
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' "s/^${key}=.*/${key}=${value}/" "$GRADLE_PROPS"
    else
        sed -i "s/^${key}=.*/${key}=${value}/" "$GRADLE_PROPS"
    fi
}

updated_any=0
if grep -q '^version=' "$GRADLE_PROPS"; then
    replace_property "version" "$NEW_VERSION"
    updated_any=1
fi
if grep -q '^VERSION=' "$GRADLE_PROPS"; then
    replace_property "VERSION" "$NEW_VERSION"
    updated_any=1
fi

if [[ "$updated_any" -eq 0 ]]; then
    echo "ERROR: Could not update version property in $GRADLE_PROPS" >&2
    exit 1
fi

echo "Version updated: $CURRENT_RAW_VERSION -> $NEW_VERSION"
