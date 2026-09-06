#!/usr/bin/env bash
set -euo pipefail

: "${KAST_TEST_RELEASE_VERSION:?}"
case "${1:-}" in
  --version)
    printf 'kast %s (IntelliJ sidecar)\n' "$KAST_TEST_RELEASE_VERSION"
    ;;
  --schema)
    printf '%s\n' '{"operationRegistry":{},"cliProjection":{"localCommands":[]}}'
    ;;
  "")
    printf '%s\n' '{"operation":"inspect","runtime":"stopped","status":"complete","control":{"execution":"isolated-intellij-sidecar"},"workspace":{"cache":{"type":"absent"}}}'
    ;;
  *)
    exit 64
    ;;
esac
