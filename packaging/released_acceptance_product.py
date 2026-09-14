"""Admit original release assets through the tagged installer in an owned fixture."""
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from acceptance_idea import digest
from released_payload_identity import verify_payloads


class ReleaseFailure(Enum):
    SOURCE = 'release-source-identity-rejected'
    ASSETS = 'release-assets-rejected'
    INSTALLER = 'release-installer-rejected'
    OWNERSHIP = 'release-installation-ownership-rejected'
    MANIFEST = 'release-installation-manifest-rejected'
    PAYLOAD = 'release-installed-payload-changed'
    INVENTORY = 'release-advertised-inventory-rejected'
    SESSION = 'release-shell-session-rejected'
    UPGRADE = 'release-upgrade-rejected'
    COORDINATOR = 'release-coordinator-lifecycle-rejected'


class ReleaseRejected(ValueError):
    def __init__(self, failure: ReleaseFailure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class ReleaseInputs:
    version: str
    commit: str
    installer: Path
    installerSha256: str
    control: Path
    controlSha256: str
    plugin: Path
    hostedPluginSha256: str
    dataDirectory: str

    @property
    def payload(self):
        return hashlib.sha256((self.controlSha256 + '\n' + self.hostedPluginSha256 + '\n').encode()).hexdigest()


@dataclass(frozen=True)
class ReleaseAssetIdentity:
    version: str
    sourceCommit: str
    installerSha256: str
    controlSha256: str
    hostedPluginSha256: str

    @classmethod
    def from_inputs(cls, inputs: ReleaseInputs):
        return cls(inputs.version, inputs.commit, inputs.installerSha256,
                   inputs.controlSha256, inputs.hostedPluginSha256)


@dataclass(frozen=True)
class ReleasedProduct:
    schemaVersion: int
    ownedRoot: str
    product: str
    executable: str
    pluginsDirectory: str
    version: str
    sourceCommit: str
    installerSha256: str
    controlSha256: str
    hostedPluginSha256: str
    payloadIdentity: str
    installationManifestSha256: str


def _physical(path: Path):
    return path.is_absolute() and path.exists() and path.resolve() == path and not path.is_symlink()


def admit_release(repo: Path, assets: Path, version: str, idea, source) -> ReleaseInputs:
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version) or not source.clean:
        raise ReleaseRejected(ReleaseFailure.SOURCE)
    tag = subprocess.run(['git', 'rev-parse', '--verify', f'refs/tags/v{version}^{{commit}}'],
                         cwd=repo, check=True, capture_output=True, text=True, timeout=10).stdout.strip()
    installer = repo / 'install.sh'
    if tag != source.commit or not _physical(installer):
        raise ReleaseRejected(ReleaseFailure.SOURCE)
    return admit_release_assets(assets, version, idea, installer, tag)


def admit_release_assets(assets: Path, version: str, idea, installer: Path, commit: str) -> ReleaseInputs:
    """Read archive identity after the caller has admitted the version tag and tagged installer."""
    if (not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version)
            or not re.fullmatch(r'[0-9a-f]{40}', commit) or not _physical(installer)):
        raise ReleaseRejected(ReleaseFailure.SOURCE)
    if not _physical(assets) or not assets.is_dir():
        raise ReleaseRejected(ReleaseFailure.ASSETS)
    metadata = json.loads((idea.home / 'Resources/product-info.json').read_text())
    directory = metadata.get('dataDirectoryName')
    if not isinstance(directory, str) or not re.fullmatch(r'[A-Za-z0-9._-]+', directory) or directory in ('.', '..'):
        raise ReleaseRejected(ReleaseFailure.ASSETS)
    control = assets / f'kast-control-v{version}-macos-aarch64.tar.gz'
    plugin = assets / f'kast-ide-hosted-v{version}-idea-{idea.build.split(".")[0]}.zip'
    hashes = []
    for archive in (control, plugin):
        checksum = archive.with_name(archive.name + '.sha256')
        if (not _physical(archive) or not archive.is_file() or archive.stat().st_size > 1073741824
                or not _physical(checksum) or not checksum.is_file() or checksum.stat().st_size > 512):
            raise ReleaseRejected(ReleaseFailure.ASSETS)
        actual = digest(archive)
        if checksum.read_text() != f'{actual}  {archive.name}\n':
            raise ReleaseRejected(ReleaseFailure.ASSETS)
        hashes.append(actual)
    return ReleaseInputs(version, commit, installer, digest(installer), control, hashes[0], plugin, hashes[1], directory)


def install_release(isolation, release: ReleaseInputs, idea) -> ReleasedProduct:
    root = isolation.root
    if root == Path('/') or not _physical(root) or root.stat().st_mode & 0o777 != 0o700:
        raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
    assets = root / 'released-assets'
    assets.mkdir(mode=0o700)
    for archive, expected in ((release.control, release.controlSha256), (release.plugin, release.hostedPluginSha256)):
        for original in (archive, archive.with_name(archive.name + '.sha256')):
            shutil.copyfile(original, assets / original.name)
        if digest(archive) != expected or digest(assets / archive.name) != expected:
            raise ReleaseRejected(ReleaseFailure.ASSETS)
    if digest(release.installer) != release.installerSha256:
        raise ReleaseRejected(ReleaseFailure.SOURCE)
    environment = dict(isolation.environment)
    environment.update(KAST_INSTALL_ASSETS_DIRECTORY=str(assets), KAST_INSTALL_ROOT=str(root / 'installation'),
                       KAST_BIN_DIR=str(root / 'bin'), KAST_ENABLE_LAUNCHD='0', KAST_ENABLE_APP_SERVER='0',
                       KAST_INSTALL_REFRESH_APP_SERVER='0')
    log = root / 'released-install.private.log'
    with log.open('xb') as output:
        log.chmod(0o600)
        result = subprocess.run([str(isolation.tools['bash']), str(release.installer), '--version', release.version,
                                 '--idea-home', str(idea.home)], cwd=root / 'workspace', env=environment,
                                stdout=output, stderr=subprocess.STDOUT, timeout=180)
    if result.returncode != 0:
        raise ReleaseRejected(ReleaseFailure.INSTALLER)
    product = root / 'installation/versions' / f'{release.version}-{release.payload}'
    plugins = root / 'home/Library/Application Support/JetBrains' / release.dataDirectory / 'plugins'
    manifest = product / 'installation.json'
    if (not _physical(product) or not product.is_dir() or not _physical(manifest)
            or manifest.stat().st_size > 67108864 or not _physical(plugins)
            or (root / 'installation/current').resolve() != product
            or (root / 'bin/kast').resolve() != product / 'bin/kast-complete'):
        raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
    document = json.loads(manifest.read_text())
    expected = {'schemaVersion': 2, 'semanticVersion': release.version, 'installationRoot': str(product),
                'payloadIdentity': 'sha256:' + release.payload, 'controlSha256': 'sha256:' + release.controlSha256,
                'hostedPluginSha256': 'sha256:' + release.hostedPluginSha256,
                'codexHome': isolation.environment['CODEX_HOME'], 'configuration': str(product / 'config/environment'),
                'workspaceRegistry': str(product / 'config/workspaces.json'), 'stateRoot': str(product / 'state')}
    if any(document.get(key) != value for key, value in expected.items()):
        raise ReleaseRejected(ReleaseFailure.MANIFEST)
    try:
        verify_payloads(product, plugins, assets / release.control.name, assets / release.plugin.name, document)
    except (OSError, ValueError, KeyError, TypeError):
        raise ReleaseRejected(ReleaseFailure.PAYLOAD) from None
    if (digest(release.installer) != release.installerSha256 or digest(release.control) != release.controlSha256
            or digest(release.plugin) != release.hostedPluginSha256
            or digest(assets / release.control.name) != release.controlSha256
            or digest(assets / release.plugin.name) != release.hostedPluginSha256):
        raise ReleaseRejected(ReleaseFailure.PAYLOAD)
    admitted = ReleasedProduct(1, str(root), str(product), str(product / 'bin/kast-complete'), str(plugins),
        release.version, release.commit, release.installerSha256, release.controlSha256, release.hostedPluginSha256,
        'sha256:' + release.payload, digest(manifest))
    witness = root / 'released-product-admission.json'
    with witness.open('x') as output:
        json.dump(asdict(admitted), output, separators=(',', ':'))
    witness.chmod(0o600)
    isolation.adopt_product(product)
    return admitted


def product_executable(product: Path, owned_root: Path) -> Path:
    """Select a wrapper only from the explicit fixture's admitted installed manifest."""
    witness = owned_root / 'released-product-admission.json'
    if not witness.exists():
        if product != owned_root / 'product':
            raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
        return product / 'bin/kast'
    if owned_root == Path('/') or not _physical(witness) or witness.stat().st_size > 8192:
        raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
    record = ReleasedProduct(**json.loads(witness.read_text()))
    if (not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', record.version)
            or not re.fullmatch(r'sha256:[0-9a-f]{64}', record.payloadIdentity)):
        raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
    expected = owned_root / 'installation/versions' / f'{record.version}-{record.payloadIdentity.removeprefix("sha256:")}'
    if (record.schemaVersion != 1 or record.ownedRoot != str(owned_root) or record.product != str(product)
            or product != expected or not _physical(product) or record.executable != str(product / 'bin/kast-complete')
            or digest(product / 'installation.json') != record.installationManifestSha256
            or not _physical(Path(record.executable))):
        raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
    manifest = json.loads((product / 'installation.json').read_text())
    entries = [item for item in manifest['payloadFiles'] if item['path'] == 'bin/kast-complete']
    if len(entries) != 1 or entries[0]['sha256'] != 'sha256:' + digest(Path(record.executable)):
        raise ReleaseRejected(ReleaseFailure.PAYLOAD)
    return Path(record.executable)
