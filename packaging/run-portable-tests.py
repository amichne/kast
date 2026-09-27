#!/usr/bin/env python3
"""Run every offline packaging test with an owned home, temp tree, and tool path."""
from pathlib import Path
import os
import platform
import re
import select
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent
NATIVE_ENTRYPOINTS = {'test-installed-codex-host.py'}
SKIP_SUMMARY = re.compile(r'\bskipped=([0-9]+)\b')


def expected_skips() -> dict[str, int]:
    permitted = {}
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        permitted['test-install-checkout.py'] = 15
    if not hasattr(select, 'kqueue'):
        permitted['test-hosted-peer-probe.py'] = 1
        permitted['test-hosted-read-regression.py'] = 1
    return permitted


def main() -> int:
    tests = tuple(sorted((ROOT / 'packaging').glob('test-*.py')))
    offline = tuple(path for path in tests if path.name not in NATIVE_ENTRYPOINTS)
    if not offline or {path.name for path in tests if path not in offline} != NATIVE_ENTRYPOINTS:
        print('portable-tests: test inventory rejected', file=sys.stderr)
        return 2
    permitted_skips = expected_skips()
    with tempfile.TemporaryDirectory(prefix='kast-portable-') as directory:
        owned = Path(directory).resolve()
        for name in ('home', 'tmp', 'config', 'data', 'state', 'cache'):
            (owned / name).mkdir(mode=0o700)
        environment = {
            'PATH': str(Path(sys.executable).resolve().parent) + os.pathsep + os.defpath,
            'HOME': str(owned / 'home'), 'TMPDIR': str(owned / 'tmp'),
            'TMP': str(owned / 'tmp'), 'TEMP': str(owned / 'tmp'),
            'XDG_CONFIG_HOME': str(owned / 'config'), 'XDG_DATA_HOME': str(owned / 'data'),
            'XDG_STATE_HOME': str(owned / 'state'), 'XDG_CACHE_HOME': str(owned / 'cache'),
            'PYTHONDONTWRITEBYTECODE': '1', 'LC_ALL': 'C', 'TZ': 'UTC',
        }
        for path in offline:
            log = owned / f'{path.stem}.log'
            with log.open('xb') as output:
                try:
                    result = subprocess.run([sys.executable, str(path)], cwd=ROOT, env=environment,
                                            stdout=output, stderr=subprocess.STDOUT, timeout=180)
                except subprocess.TimeoutExpired:
                    print(f'portable-tests: {path.name}: deadline-exceeded', file=sys.stderr)
                    return 1
            if result.returncode != 0:
                print(f'portable-tests: {path.name}: exit {result.returncode}', file=sys.stderr)
                with log.open('rb') as evidence:
                    evidence.seek(max(0, log.stat().st_size - 4096))
                    print(evidence.read().decode(errors='replace'), file=sys.stderr)
                return 1
            with log.open('rb') as evidence:
                evidence.seek(max(0, log.stat().st_size - 4096))
                summary = evidence.read().decode(errors='replace')
            observed_skips = sum(int(count) for count in SKIP_SUMMARY.findall(summary))
            if observed_skips != permitted_skips.get(path.name, 0):
                print(f'portable-tests: {path.name}: unexpected skipped cases ({observed_skips})', file=sys.stderr)
                print(summary, file=sys.stderr)
                return 1
            print(f'portable-tests: {path.name}: passed, {observed_skips} skipped', flush=True)
    print(f'portable-tests: {len(offline)} suites passed; {sum(permitted_skips.values())} platform cases skipped')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
