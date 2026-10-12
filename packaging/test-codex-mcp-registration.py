"""The force preflight admits only exact historical Kast MCP slots without old payload ownership."""
from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


@dataclass(frozen=True)
class Transport:
    command: str
    args: tuple[str, ...] = ()
    type: str = 'stdio'


@dataclass(frozen=True)
class Server:
    transport: Transport
    name: str = 'kast'


class CodexResetPreflightTest(unittest.TestCase):
    def run_preflight(self, root: Path, command: str, *, action='check-reset', args=()):
        binary = root / 'bin/codex'
        binary.parent.mkdir(exist_ok=True)
        document = root / 'servers.json'
        document.write_text(json.dumps([asdict(Server(Transport(command, args)))]))
        log = root / 'calls'
        binary.write_text('#!/bin/sh\n'
            'printf "%s\\n" "$*" >> ' + shlex.quote(str(log)) + '\n'
            '[ "$*" = "mcp list --json" ] || exit 91\n'
            'cat ' + shlex.quote(str(document)) + '\n')
        binary.chmod(0o755)
        home = root / 'home'
        home.mkdir(exist_ok=True)
        install = home / '.local/share/kast'
        environment = {'HOME': str(home), 'PATH': str(binary.parent) + os.pathsep + os.defpath}
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('codex-mcp-registration.py')),
            action, str(install)], env=environment, text=True, capture_output=True, timeout=10)
        self.assertEqual(['mcp list --json'], log.read_text().splitlines())
        return result

    def test_known_stale_commands_pass_without_old_installation_or_network(self):
        for relative in ('.local/share/kast/current/bin/kast-mcp-complete',
                         '.local/share/kast/installation/bin/kast-mcp-complete',
                         '.kast/bin/kast-mcp-complete', '.kast/installation/bin/kast-mcp-complete'):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory(prefix='kast-mcp-reset-') as directory:
                root = Path(directory).resolve()
                result = self.run_preflight(root, str(root / 'home' / relative))
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse((root / 'home/.local/share/kast').exists())

    def test_foreign_command_and_extra_arguments_reject_without_registration_changes(self):
        for foreign in (True, False):
            with tempfile.TemporaryDirectory(prefix='kast-mcp-reset-rejected-') as directory:
                root = Path(directory).resolve()
                command = '/foreign/kast' if foreign else str(root / 'home/.local/share/kast/current/bin/kast-mcp-complete')
                result = self.run_preflight(root, command, args=() if foreign else ('--foreign',))
                self.assertEqual(65, result.returncode, result.stderr)

    def test_normal_install_still_needs_admitted_legacy_ownership(self):
        with tempfile.TemporaryDirectory(prefix='kast-mcp-ordinary-') as directory:
            root = Path(directory).resolve()
            result = self.run_preflight(root, str(root / 'home/.local/share/kast/current/bin/kast-mcp-complete'), action='check')
            self.assertEqual(65, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
