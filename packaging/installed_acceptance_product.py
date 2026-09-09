"""Materialize an exact version-owned fixture from admitted build artifacts, without services."""
import hashlib
import json
from pathlib import Path
import re
import tarfile
from acceptance_environment import EnvironmentFailure, EnvironmentRejected


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()


def stage_versioned_product(isolation, source: Path, runtime: Path) -> Path:
    product = isolation.stage_product(source)
    metadata = json.loads((product / 'share/kast/semantic-runtime.json').read_text())
    version = metadata['productVersion']
    if not isinstance(version, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,95}', version):
        raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
    runtime_digest = sha256(runtime)
    if metadata['archive']['sha256'] != 'sha256:' + runtime_digest:
        raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
    complete = product / 'bin/kast-complete'
    if not complete.exists():
        complete.write_text('#!/bin/sh\napp_home=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P) || exit 1\nexport KAST_CONFIGURATION_FILE="$app_home/config/environment"\nexec "$app_home/bin/kast" "$@"\n')
        complete.chmod(0o755)
    control_archive = isolation.root / 'fixture-control.tar'
    with tarfile.open(control_archive, 'w') as archive:
        for name in ('bin', 'lib', 'share'):
            directory = product / name
            if not directory.is_dir() or directory.is_symlink():
                raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
            for candidate in directory.rglob('*'):
                if candidate.is_symlink():
                    raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
            archive.add(directory, arcname=name)
    control_digest = sha256(control_archive)
    payload = hashlib.sha256((control_digest + '\n' + runtime_digest + '\n').encode()).hexdigest()
    versions = isolation.root / 'installation/versions'
    versions.mkdir(parents=True)
    root = versions / (version + '-' + payload)
    product.rename(root)
    isolation.adopt_product(root)
    inventory = []
    for directory in ('bin', 'lib', 'share'):
        for candidate in sorted((root / directory).rglob('*')):
            if candidate.is_file():
                inventory.append({'path': candidate.relative_to(root).as_posix(),
                                  'sha256': 'sha256:' + sha256(candidate), 'mode': candidate.stat().st_mode & 0o777})
    run = root / 'state/run'
    alias = Path('/tmp') / ('kast-uds-' + hashlib.sha256(str(run).encode()).hexdigest()[:32])
    label = 'io.github.amichne.kast.broker.' + hashlib.sha256(str(root).encode()).hexdigest()[:32]
    anchors = [
        {'kind': 'socket-alias', 'path': str(alias), 'expectedLinkTarget': str(run),
         'identityReceipt': str(run / 'endpoint-alias.json'), 'ownership': 'declared-not-observed'},
        {'kind': 'login', 'path': str(Path(isolation.environment['HOME']) / 'Library/LaunchAgents' / (label + '.login.plist')),
         'expectedExecutable': str(root / 'bin/kast'), 'expectedLabel': label + '.login', 'ownership': 'declared-not-observed'},
    ]
    manifest = {'schemaVersion': 1, 'semanticVersion': version, 'installationRoot': str(root),
        'payloadIdentity': 'sha256:' + payload, 'controlSha256': 'sha256:' + control_digest,
        'runtimeSha256': 'sha256:' + runtime_digest, 'codexHome': isolation.environment['CODEX_HOME'],
        'configuration': str(root / 'config/environment'), 'workspaceRegistry': str(root / 'config/workspaces.json'),
        'stateRoot': str(root / 'state'), 'payloadFiles': inventory, 'externalAnchors': anchors,
        'retention': {'payload': 'until-explicit-uninstall', 'config': 'until-explicit-uninstall',
                      'state': 'after-exact-process-retirement', 'externalAnchors': 'after-live-identity-match'}}
    (root / 'installation.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
    (root / 'installation.json').chmod(0o600)
    return root
