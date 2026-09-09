"""Pinned IDEA input for installed acceptance; no global application installation."""
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import subprocess
import tempfile

BUILD = '262.9437.185'
URL = 'https://download.jetbrains.com/idea/idea-2026.2.1-aarch64.dmg'
SHA256 = 'b9c521ba766f7e5372e9d05f6d68442ada13f34ce758af318ca21f017c0bb19f'
SIZE = 1512591157
AUTHORITY = 'https://data.services.jetbrains.com/products/releases?code=IIU&latest=false&type=release'


def admit_home(path: Path) -> Path:
    if not path.is_absolute() or path.is_symlink() or not path.is_dir():
        raise ValueError('IDEA_HOME_REJECTED')
    home = path.resolve(strict=True)
    info = home / 'Resources/product-info.json'
    if not info.is_file() or info.is_symlink() or info.stat().st_size > 1024 * 1024:
        raise ValueError('IDEA_METADATA_REJECTED')
    document = json.loads(info.read_text())
    if document.get('buildNumber') != BUILD:
        raise ValueError('IDEA_BUILD_REJECTED')
    if not any(item.get('os') == 'macOS' and item.get('arch') == 'aarch64'
               for item in document.get('launch', [])):
        raise ValueError('IDEA_PLATFORM_REJECTED')
    java = home / 'jbr/Contents/Home/bin/java'
    if not java.is_file() or not os.access(java, os.X_OK):
        raise ValueError('IDEA_BUNDLED_RUNTIME_REJECTED')
    return home


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def provision(cache: Path) -> Path:
    # A fresh exclusive directory is the only writable authority. Existing caches
    # are never accepted as distribution proof and cannot redirect extraction.
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise ValueError('ACCEPTANCE_PLATFORM_REJECTED')
    if not cache.is_absolute() or cache.is_symlink():
        raise ValueError('ACCEPTANCE_CACHE_REJECTED')
    cache.mkdir(parents=True, exist_ok=True)
    owned = Path(tempfile.mkdtemp(prefix='idea-', dir=cache.resolve()))
    archive = owned / 'idea.dmg'
    mount = owned / 'mount'
    mount.mkdir()
    subprocess.run(['/usr/bin/curl', '--fail', '--location', '--proto', '=https', '--proto-redir', '=https',
                    '--connect-timeout', '30', '--max-time', '1200', '--max-filesize', str(SIZE),
                    '--output', str(archive), URL], check=True, timeout=1210)
    if archive.stat().st_size != SIZE or digest(archive) != SHA256:
        raise ValueError('IDEA_ARCHIVE_IDENTITY_REJECTED')
    mounted = False
    try:
        result = subprocess.run(['/usr/bin/hdiutil', 'attach', '-readonly', '-nobrowse', '-noautoopen',
                                 '-mountpoint', str(mount), '-plist', str(archive)],
                                check=True, capture_output=True, timeout=120)
        entities = plistlib.loads(result.stdout).get('system-entities', [])
        mounted = any(item.get('mount-point') == str(mount) for item in entities)
        if not mounted:
            raise ValueError('IDEA_MOUNT_RECEIPT_REJECTED')
        apps = list(mount.glob('*.app'))
        if len(apps) != 1 or apps[0].is_symlink():
            raise ValueError('IDEA_APPLICATION_REJECTED')
        subprocess.run(['/usr/bin/ditto', str(apps[0]), str(owned / 'IDEA.app')], check=True, timeout=600)
    finally:
        if mounted:
            subprocess.run(['/usr/bin/hdiutil', 'detach', str(mount)], check=True, timeout=120)
    home = admit_home(owned / 'IDEA.app/Contents')
    (owned / 'input-provenance.json').write_text(json.dumps({
        'schemaVersion': 1, 'authority': AUTHORITY, 'url': URL, 'sha256': SHA256,
        'bytes': SIZE, 'ideaBuild': BUILD, 'home': str(home)}, indent=2) + '\n')
    archive.unlink()
    return home
