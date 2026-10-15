#!/usr/bin/env python3
"""Prove shared socket retirement after a private launchd job stops its owner."""
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import hashlib
import json
import os
import plistlib
import socket
import subprocess
import tempfile
import time


@dataclass(frozen=True)
class ExpectedRemoval:
    root: str
    type: str = 'REMOVED'
    operation: str = 'UNINSTALL_FORCE'


def run(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix='kr-', dir='/tmp') as directory:
        owned = Path(directory).resolve()
        task_home = owned / 'h'
        root = task_home / '.local/share/kast'
        daemon = root / 'installation/bin/kast-daemon'
        daemon.parent.mkdir(parents=True)
        (root / 'management.json').write_text('corrupt receipt')
        public = task_home / '.codex/app-server-control/app-server-control.sock'
        public.parent.mkdir(parents=True)
        source = Path(__file__).resolve().parents[1] / 'distribution/cli/src/test/resources/lifecycle/codex-listener.c'
        subprocess.run(['/usr/bin/cc', str(source), '-o', str(daemon)], check=True, timeout=15)
        label = 'io.github.amichne.kast.broker.' + hashlib.sha256(str(root / 'installation').encode()).hexdigest()[:32]
        plist = owned / 'job.plist'
        plist.write_bytes(plistlib.dumps({
            'Label': label,
            'ProgramArguments': [str(daemon), 'app-server', '--listen', f'unix://{public}'],
            'RunAtLoad': True,
            'StandardOutPath': str(owned / 'job.log'),
            'StandardErrorPath': str(owned / 'job.log'),
        }))
        job = f'gui/{os.getuid()}/{label}'
        connection = socket.socket(socket.AF_UNIX)
        connection.settimeout(5)
        try:
            setup = subprocess.run(['/bin/launchctl', 'bootstrap', f'gui/{os.getuid()}', str(plist)],
                                   capture_output=True, text=True, timeout=15)
            assert setup.returncode == 0, 'fixture bootstrap failed: ' + setup.stderr
            deadline = time.monotonic() + 5
            while True:
                try:
                    connection.connect(str(public))
                    break
                except (FileNotFoundError, ConnectionRefusedError):
                    assert time.monotonic() < deadline, 'fixture job did not become ready'
                    time.sleep(0.05)
            assert public.is_socket(), 'fixture socket is absent'
            assert subprocess.run(['/bin/launchctl', 'list', label], capture_output=True, timeout=10).returncode == 0
            environment = {'HOME': str(task_home), 'PATH': os.defpath, 'KAST_INSTALL_ROOT': str(root), 'NO_COLOR': '1'}
            result = subprocess.run([str(binary), '--internal-install', 'reset'], env=environment,
                                    capture_output=True, text=True, timeout=45)
            assert result.returncode == 0, result.stdout + result.stderr
            assert json.loads(result.stdout) == asdict(ExpectedRemoval(str(root))), result.stdout
            assert connection.recv(1) == b'', 'launchd left the existing connection open'
            assert not public.exists(), 'reset reported success after launchd exit but left its shared socket'
            assert public.with_name(public.name + '.lock').is_file(), 'shared socket lock was removed'
            assert not root.exists(), 'reset kept the old installation'
            assert subprocess.run(['/bin/launchctl', 'list', label], capture_output=True, timeout=10).returncode == 113
            print('passed: capture preceded launchd exit, the existing connection closed, and the shared socket retired')
        finally:
            connection.close()
            subprocess.run(['/bin/launchctl', 'bootout', job], capture_output=True, timeout=15)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--management', required=True, type=Path)
    arguments = parser.parse_args()
    run(arguments.management.resolve(strict=True))


if __name__ == '__main__':
    main()
