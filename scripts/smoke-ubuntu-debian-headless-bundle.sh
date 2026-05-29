#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export KAST_UBUNTU_DEBIAN_BUNDLE_KIND=headless
exec "${script_dir}/smoke-ubuntu-debian-bundle.sh" "$@"
