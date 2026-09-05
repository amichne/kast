#!/usr/bin/env bash
# Shared product gate for GitHub Actions and a local detached worktree.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$root"
sha="$(git rev-parse HEAD)"
bash .github/scripts/release/admit-source.sh --repository-root "$root" --expected-source-revision "$sha" >/dev/null
if git symbolic-ref -q HEAD >/dev/null; then
  echo 'ci-preflight: detached checkout required; use reproduce.sh' >&2
  exit 1
fi
export JAVA_HOME="${KAST_RELEASE_JDK_25:?Set KAST_RELEASE_JDK_25 to a Java 25 home}"
: "${KAST_RELEASE_JDK_17:?Set KAST_RELEASE_JDK_17 to a Java 17 home}"
: "${KAST_RELEASE_JDK_21:?Set KAST_RELEASE_JDK_21 to a Java 21 home}"
for tool in python3 node npm mint gh; do command -v "$tool" >/dev/null; done
python3 - <<'PY'
import json, os, platform, subprocess, sys
from pathlib import Path
sys.path.insert(0, 'integration-tests')
from gradle_import_acceptance import Jdk
for feature in (17, 21, 25):
    Jdk.parse(f'{feature}:{os.environ[f"KAST_RELEASE_JDK_{feature}"]}')
if platform.system() != 'Darwin' or platform.machine() not in ('arm64', 'aarch64'):
    raise SystemExit('ci-preflight: macOS arm64 required')
if sys.version_info[:2] != (3, 12):
    raise SystemExit('ci-preflight: Python 3.12 required, matching .python-version')
node = subprocess.check_output(['node', '--version'], text=True).strip()
mint = subprocess.check_output(['mint', '--version'], text=True).strip()
if not node.startswith('v24.') or mint != '4.2.841':
    raise SystemExit('ci-preflight: Node 24 and mint 4.2.841 required')
print(json.dumps({'stage': 'ci-preflight', 'outcome': 'admitted', 'python': platform.python_version(), 'node': node, 'mint': mint}))
PY
version="$(cat distribution/release/candidate-version.txt)"
python3 integration-tests/release_upgrade_acceptance.py --candidate-version "${version#v}"
bash packaging/test-installer.sh
bash .github/scripts/release/build-assets.sh --version "${version#v}" --source-revision "$sha"
bash .github/scripts/release/test-admit-source.sh
bash .github/scripts/release/test-module-knowledge-authority.sh
python3 distribution/release/test_verify_assets_authority.py
python3 distribution/release/verify_assets.py --self-test
python3 integration-tests/test_enterprise_acceptance.py
