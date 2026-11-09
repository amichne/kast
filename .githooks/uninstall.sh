#!/usr/bin/env bash
set -euo pipefail

destination="$(git rev-parse --git-path hooks)/pre-push"
if [[ -f "$destination" && ! -L "$destination" ]] &&
  grep -Fxq '# kast-pre-push-dispatch' "$destination"; then
  rm -- "$destination"
  echo "Removed Kast pre-push verification: $destination"
else
  echo "No Kast-owned pre-push verification to remove."
fi
