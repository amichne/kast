#!/usr/bin/env bash
# Source this in each task shell.
export KAST_CLOUD_ROOT="${KAST_CLOUD_ROOT:-/workspace/.cloud/kast-dev}"
export MISE_DATA_DIR="${MISE_DATA_DIR:-/workspace/.cloud/kast-051/mise-data}"
export MISE_CACHE_DIR="${MISE_CACHE_DIR:-/workspace/.cloud/kast-051/mise-cache}"
export MISE_CONFIG_DIR="${MISE_CONFIG_DIR:-/workspace/.cloud/kast-051/mise-config}"
export MISE_STATE_DIR="${MISE_STATE_DIR:-/workspace/.cloud/kast-051/mise-state}"
export MISE_TRUSTED_CONFIG_PATHS="$KAST_CLOUD_ROOT/release-checkout"
export JAVA_HOME="$MISE_DATA_DIR/installs/java/liberica-nik-openjdk25-25.0.2+1"
export PATH="$KAST_CLOUD_ROOT/bin:$MISE_DATA_DIR/installs/node/24.21.0/bin:$JAVA_HOME/bin:$PATH"
