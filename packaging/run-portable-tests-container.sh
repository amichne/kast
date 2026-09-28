#!/usr/bin/env bash
set -euo pipefail

repo=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd -P)
image=kast-portable-tests:local
docker build --file "$repo/packaging/portable.Dockerfile" --tag "$image" \
  "$repo/packaging"
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" --pids-limit 512 \
  --tmpfs /tmp:rw,exec,mode=1777 \
  --mount "type=bind,source=$repo,target=/src,readonly" \
  --workdir /src "$image"
