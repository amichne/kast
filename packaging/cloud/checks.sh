#!/usr/bin/env bash
set -euo pipefail
umask 022
profile=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$(CDPATH='' cd -- "$profile/../.." && pwd -P)
: "${PI_EVAL_ROOT:?Source the generated activate.sh first}"
node "$profile/smoke.mjs" --refresh-catalog
cd "$repo"
node --test experiments/host-observation/pi_evaluation*.test.mjs
node --test packaging/cloud/smoke-acceptance.test.mjs
python3 packaging/run-portable-tests.py
