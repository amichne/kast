"""Local Gradle installation must use the versioned lifecycle without sibling deletion."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class LocalInstallationTest(unittest.TestCase):
    def test_local_adapter_delegates_to_versioned_installer_and_preserves_legacy_siblings(self):
        with tempfile.TemporaryDirectory(prefix='kast-local-') as directory:
            root = Path(directory).resolve()
            (root / 'packaging').mkdir()
            script = root / 'packaging/install-local.sh'
            shutil.copy2(Path(__file__).with_name('install-local.sh'), script)
            record = root / 'installer-arguments'
            (root / 'install.sh').write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "' + str(record) + '"\n')
            product = root / 'product'
            (product / 'bin').mkdir(parents=True)
            (product / 'share/kast').mkdir(parents=True)
            (product / 'bin/kast').write_text('#!/bin/sh\nexit 0\n')
            (product / 'bin/kast').chmod(0o755)
            (product / 'share/kast/semantic-runtime.json').write_text(json.dumps({'productVersion': '1.2.3'}))
            runtime = root / 'kast-semantic-runtime-1.2.3-macos-aarch64.zip'
            runtime.write_text('fixture archive')
            prefix = root / 'prefix'
            markers = []
            for name in ('local', 'control', 'runtime'):
                marker = prefix / 'share/kast' / name / 'unproven-owner'
                marker.parent.mkdir(parents=True)
                marker.write_text('preserve')
                markers.append(marker)
            env = {'PATH': '/usr/bin:/bin', 'HOME': str(root), 'TMPDIR': str(root),
                   'KAST_LOCAL_PREFIX': str(prefix), 'KAST_LOCAL_CONTROL_PRODUCT': str(product),
                   'KAST_LOCAL_RUNTIME_ARCHIVE': str(runtime), 'KAST_LOCAL_JAVA_EXECUTABLE': '/usr/bin/true',
                   'KAST_LOCAL_JAVA_HOME': str(root)}
            result = subprocess.run(['bash', str(script)], env=env, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(all(path.is_file() and path.read_text() == 'preserve' for path in markers),
                            'unproven legacy siblings must survive local installation')
            arguments = record.read_text().splitlines()
            self.assertEqual('1.2.3', arguments[arguments.index('--version') + 1])
            self.assertEqual(str(prefix / 'share/kast'), arguments[arguments.index('--install-root') + 1])
            self.assertIn('--assets-directory', arguments)


if __name__ == '__main__':
    unittest.main()
