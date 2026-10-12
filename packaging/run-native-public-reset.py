#!/usr/bin/env python3
"""Exercise the native public reset with owned processes and files under a disposable HOME."""
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import tempfile
import threading


@dataclass(frozen=True)
class ExpectedRemoval:
    root: str
    type: str = 'REMOVED'
    operation: str = 'UNINSTALL_FORCE'


def run(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix='kast-native-public-reset-') as directory:
        owned = Path(directory).resolve()
        home = owned / 'home'
        root = home / '.local/share/kast'
        (root / 'installation/bin').mkdir(parents=True)
        (root / 'management.json').write_text('corrupt receipt')
        command = home / '.local/bin/kast'
        command.parent.mkdir(parents=True)
        command.write_text('old facade')
        legacy = home / '.kast'
        legacy.mkdir()
        (legacy / 'sentinel').write_text('old state')
        artifact = root.parent / '.kast-old'
        artifact.mkdir()
        (artifact / 'sentinel').write_text('old payload')
        protected = owned / 'protected'
        protected.mkdir()
        (protected / 'sentinel').write_text('keep')
        (artifact / 'outside-link').symlink_to(protected)
        daemon = root / 'installation/bin/kast-daemon'
        shutil.copyfile('/bin/sleep', daemon)
        daemon.chmod(0o755)
        child = subprocess.Popen([str(daemon), '60'])
        watcher = threading.Thread(target=child.wait)
        watcher.start()
        unrelated = subprocess.Popen(['/bin/sleep', '60'])
        try:
            environment = {'HOME': str(home), 'PATH': os.defpath, 'KAST_INSTALL_ROOT': str(root), 'NO_COLOR': '1'}
            plan = subprocess.run([str(binary), '--internal-install', 'preflight-reset'], env=environment,
                capture_output=True, text=True, timeout=15)
            assert plan.returncode == 0, plan.stderr
            assert plan.stdout.strip() == str(command), plan.stdout
            assert root.exists() and child.poll() is None, 'preflight changed installed state'
            result = subprocess.run([str(binary), '--internal-install', 'reset'], env=environment,
                capture_output=True, text=True, timeout=45)
            assert result.returncode == 0, result.stdout + result.stderr
            assert json.loads(result.stdout) == asdict(ExpectedRemoval(str(root))), result.stdout
            watcher.join(timeout=5)
            assert not watcher.is_alive(), 'owned child did not retire'
            assert unrelated.poll() is None, 'unrelated child received a signal'
            assert not root.exists() and not legacy.exists() and not artifact.exists() and not command.exists()
            assert (protected / 'sentinel').read_text() == 'keep'
            print('passed: native reset retired the scoped process and removed stale state')
            print('passed: preflight preserved state, the unrelated process and symlink target remained')
        finally:
            if child.poll() is None:
                child.kill()
            watcher.join(timeout=5)
            unrelated.terminate()
            unrelated.wait(timeout=5)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--management', required=True, type=Path)
    arguments = parser.parse_args()
    run(arguments.management.resolve(strict=True))


if __name__ == '__main__':
    main()
