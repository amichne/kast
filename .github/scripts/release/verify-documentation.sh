#!/usr/bin/env bash
set -euo pipefail
python3 docs/tooling/likec4/generate_bundle.py --check
python3 docs/test_public_docs.py
cd docs/public
mint validate
