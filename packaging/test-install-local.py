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
            record = root / 'installer-contract'
            (root / 'install.sh').write_text(
                '#!/bin/sh\n'
                'printf "argument=%s\\n" "$@" > "' + str(record) + '"\n'
                'for key in KAST_VERSION KAST_RELEASE_BASE_URL KAST_INSTALL_ASSETS_DIRECTORY '
                'KAST_INSTALL_ROOT KAST_BIN_DIR KAST_ENABLE_LAUNCHD KAST_ENABLE_APP_SERVER; do\n'
                '  eval "value=\\${$key-}"\n'
                '  printf "%s=%s\\n" "$key" "$value" >> "' + str(record) + '"\n'
                'done\n'
            )
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
            contract = dict(line.split('=', 1) for line in record.read_text().splitlines())
            self.assertEqual('', contract['argument'])
            self.assertEqual('1.2.3', contract['KAST_VERSION'])
            self.assertEqual(str(prefix / 'share/kast'), contract['KAST_INSTALL_ROOT'])
            self.assertEqual(str(prefix / 'bin'), contract['KAST_BIN_DIR'])
            self.assertEqual('0', contract['KAST_ENABLE_LAUNCHD'])
            self.assertEqual('0', contract['KAST_ENABLE_APP_SERVER'])
            self.assertTrue(Path(contract['KAST_INSTALL_ASSETS_DIRECTORY']).name.startswith('kast-local-assets.'))


if __name__ == '__main__':
    unittest.main()
