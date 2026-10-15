#!/usr/bin/env python3
"""Exercise the native public reset with owned processes and files under a disposable HOME."""
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import json
import os
import selectors
import socket
import subprocess
import tempfile
import threading


@dataclass(frozen=True)
class ExpectedRemoval:
    root: str
    type: str = 'REMOVED'
    operation: str = 'UNINSTALL_FORCE'


def expect_signal(child: subprocess.Popen[str], expected: str) -> None:
    assert child.stdout is not None
    with selectors.DefaultSelector() as reader:
        reader.register(child.stdout, selectors.EVENT_READ)
        assert reader.select(timeout=5), f'disposable process did not report {expected}'
        assert child.stdout.readline().strip() == expected, 'disposable process rejected its setup'


def run(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix='kr-', dir='/tmp') as directory:
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
        source = Path(__file__).resolve().parents[1] / 'distribution/cli/src/test/resources/lifecycle/codex-listener.c'
        subprocess.run(['/usr/bin/cc', str(source), '-o', str(daemon)], check=True, timeout=15)
        public = home / '.codex/app-server-control/app-server-control.sock'
        public.parent.mkdir(parents=True)
        child = subprocess.Popen([str(daemon), 'app-server', '--listen', f'unix://{public}'], stdout=subprocess.PIPE, text=True)
        watcher = threading.Thread(target=child.wait)
        watcher.start()
        unrelated = subprocess.Popen(['/bin/sleep', '60'])
        connection = socket.socket(socket.AF_UNIX)
        try:
            expect_signal(child, 'ready')
            connection.settimeout(5)
            connection.connect(str(public))
            expect_signal(child, 'connected')
            environment = {'HOME': str(home), 'PATH': os.defpath, 'KAST_INSTALL_ROOT': str(root), 'NO_COLOR': '1'}
            plan = subprocess.run([str(binary), '--internal-install', 'preflight-reset'], env=environment,
                capture_output=True, text=True, timeout=15)
            assert plan.returncode == 0, plan.stderr
            assert plan.stdout.strip() == str(command), plan.stdout
            assert root.exists(), 'preflight changed installed state'
            assert child.poll() is None, f'disposable process exited before reset: {child.returncode}'
            result = subprocess.run([str(binary), '--internal-install', 'reset'], env=environment,
                capture_output=True, text=True, timeout=45)
            assert result.returncode == 0, result.stdout + result.stderr
            assert json.loads(result.stdout) == asdict(ExpectedRemoval(str(root))), result.stdout
            watcher.join(timeout=5)
            assert not watcher.is_alive(), 'owned child did not retire'
            assert unrelated.poll() is None, 'unrelated child received a signal'
            assert connection.recv(1) == b'', 'existing shared connection did not close'
            assert not public.exists(), 'owned shared socket blocks replacement'
            assert public.with_name(public.name + '.lock').is_file(), 'shared socket lock was removed'
            assert not root.exists() and not legacy.exists() and not artifact.exists() and not command.exists()
            assert len(list(root.parent.glob('.kast-reset-*.json.lock'))) == 1, 'reset removed its stable lock'
            assert (protected / 'sentinel').read_text() == 'keep'
            print('passed: native reset retired the scoped process and removed stale state')
            print('passed: preflight preserved state, the unrelated process and symlink target remained')
            print('passed: native shared connection closed, its owned socket retired, and both stable locks remained')
        finally:
            connection.close()
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
