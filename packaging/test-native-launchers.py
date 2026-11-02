"""Real launcher execution preserves canonical executable identity and opaque arguments."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class NativeLauncherTest(unittest.TestCase):
    def test_production_launchers_execute_the_canonical_target_through_an_aliased_installation(self):
        sources = Path(__file__).resolve().parents[1] / 'cli/src/main/native-launchers/bin'
        modes = {'kast': 'installer', 'kast-codex': 'codex', 'kast-mcp': 'mcp',
                 'kast-query': 'query', 'kast-tool-rpc': 'rpc'}
        with tempfile.TemporaryDirectory(prefix='kast-native-launchers-') as directory:
            root = Path(directory).resolve()
            product = root / 'product with spaces'
            (product / 'bin').mkdir(parents=True)
            native = product / 'share/kast/libexec/kast-management'
            native.parent.mkdir(parents=True)
            native.write_text('#!' + sys.executable + '\nimport sys\n'
                              'sys.stdout.write("\\n".join(sys.argv) + "\\n")\n')
            native.chmod(0o755)
            alias = root / 'selected product'
            alias.symlink_to(product, target_is_directory=True)
            home, temporary = root / 'owned home', root / 'owned temp'
            home.mkdir()
            temporary.mkdir()
            environment = {'PATH': os.defpath, 'HOME': str(home), 'TMPDIR': str(temporary),
                           'CDPATH': str(root)}
            for name, mode in modes.items():
                with self.subTest(launcher=name):
                    shutil.copy2(sources / name, product / 'bin' / name)
                    (product / 'bin' / name).chmod(0o755)
                    result = subprocess.run([str(alias / 'bin' / name), 'argument with spaces', '--flag'],
                                            env=environment, capture_output=True, text=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual([str(native), '-Djava.io.tmpdir=' + str(temporary),
                                      '-Duser.home=' + str(home), '--internal-entrypoint=' + mode,
                                      'argument with spaces', '--flag'], result.stdout.splitlines())


if __name__ == '__main__':
    unittest.main()
