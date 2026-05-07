#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

TARGET="${PUBLISH_TARGET:-}"
VERSION_CHOICE="${VERSION_CHOICE:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            TARGET="${2:-}"
            shift 2
            ;;
        --version-choice)
            VERSION_CHOICE="${2:-}"
            shift 2
            ;;
        --help|-h)
            cat <<USAGE
Usage: ./scripts/publish-on-rails.sh [--target local|snapshot|release|github] [--version-choice choice]

Version choices:
  none
  snapshot
  patch
  minor
  major
  patch-snapshot
  minor-snapshot
  major-snapshot
USAGE
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

is_valid_target() {
    case "$1" in
        local|snapshot|release|github) return 0 ;;
        *) return 1 ;;
    esac
}

is_valid_version_choice() {
    case "$1" in
        none|snapshot|patch|minor|major|patch-snapshot|minor-snapshot|major-snapshot) return 0 ;;
        *) return 1 ;;
    esac
}

choose_with_fzf() {
    local prompt="$1"
    shift
    printf "%s\n" "$@" | fzf --height=40% --reverse --prompt "$prompt"
}

choose_with_select() {
    local prompt="$1"
    shift
    local options=("$@")

    echo "$prompt"
    local idx=1
    for option in "${options[@]}"; do
        echo "  [$idx] $option"
        idx=$((idx + 1))
    done

    local selected
    while true; do
        read -r -p "Select [1-${#options[@]}]: " selected
        if [[ "$selected" =~ ^[0-9]+$ ]] && ((selected >= 1 && selected <= ${#options[@]})); then
            echo "${options[$((selected - 1))]}"
            return 0
        fi
    done
}

ensure_target() {
    if [[ -n "$TARGET" ]]; then
        if ! is_valid_target "$TARGET"; then
            echo "Invalid publish target: $TARGET" >&2
            exit 1
        fi
        return
    fi

    local options=(
        "local"
        "snapshot"
        "release"
        "github"
    )

    if command -v fzf >/dev/null 2>&1 && [[ -t 1 ]]; then
        TARGET="$(choose_with_fzf "Publish target > " "${options[@]}")"
    else
        TARGET="$(choose_with_select "Choose publish target:" "${options[@]}")"
    fi
}

ensure_version_choice() {
    if [[ -n "$VERSION_CHOICE" ]]; then
        if ! is_valid_version_choice "$VERSION_CHOICE"; then
            echo "Invalid version choice: $VERSION_CHOICE" >&2
            exit 1
        fi
        return
    fi

    local options=(
        "none"
        "snapshot"
        "patch"
        "minor"
        "major"
        "patch-snapshot"
        "minor-snapshot"
        "major-snapshot"
    )

    if command -v fzf >/dev/null 2>&1 && [[ -t 1 ]]; then
        VERSION_CHOICE="$(choose_with_fzf "Version action > " "${options[@]}")"
    else
        VERSION_CHOICE="$(choose_with_select "Choose version action:" "${options[@]}")"
    fi
}

confirm_plan() {
    if [[ ! -t 0 ]]; then
        return 0
    fi

    echo
    echo "Publication plan"
    echo "  target: $TARGET"
    echo "  version-action: $VERSION_CHOICE"
    echo

    read -r -p "Continue? [y/N] " reply
    if [[ ! "$reply" =~ ^[Yy]$ ]]; then
        echo "Aborted"
        exit 0
    fi
}

run_node() {
    local node="$1"
    make "$node"
}

ensure_target
ensure_version_choice
confirm_plan

run_node "publish-version-$VERSION_CHOICE"
run_node "publish-run-$TARGET"

echo

echo "Publication completed"
