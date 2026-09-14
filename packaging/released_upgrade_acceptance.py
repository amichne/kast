"""Optional adjacent-patch upgrade through one tagged installer and original release archives."""
from dataclasses import dataclass
import json
from pathlib import Path
import subprocess

from acceptance_idea import digest
from released_acceptance_product import (ReleaseFailure, ReleaseInputs, ReleaseRejected, ReleasedProduct,
    admit_release_assets, install_release, product_executable)
from released_payload_identity import verify_control
from released_session_acceptance import ShellSessionReceipt, inspect_shell_sessions


@dataclass(frozen=True)
class ReleasedUpgradeReceipt:
    previous: ReleasedProduct
    target: ReleasedProduct
    installerSourceCommit: str
    previousSessions: tuple[ShellSessionReceipt, ...]
    targetSessions: tuple[ShellSessionReceipt, ...]
    previousConfigurationSha256: str
    previousRegistry: str
    installerEvidence: str
    installerEvidenceSha256: str
    priorAdmission: str = 'COMPLETED'
    priorRetirement: str = 'COMPLETED'
    persistentCoordinator: str = 'not-started-unqualified'
    stockCodexUi: str = 'not-run-unqualified'


def admit_previous_release(repo, assets, version, idea, target: ReleaseInputs):
    try:
        previous_version, target_version = tuple(map(int, version.split('.'))), tuple(map(int, target.version.split('.')))
    except (ValueError, AttributeError):
        raise ReleaseRejected(ReleaseFailure.UPGRADE) from None
    if (len(previous_version) != 3 or previous_version[:2] != target_version[:2]
            or previous_version[2] + 1 != target_version[2]):
        raise ReleaseRejected(ReleaseFailure.UPGRADE)
    commit = subprocess.run(['git', 'rev-parse', '--verify', f'refs/tags/v{version}^{{commit}}'], cwd=repo,
                            check=True, capture_output=True, text=True, timeout=10).stdout.strip()
    previous = admit_release_assets(assets, version, idea, target.installer, commit)
    if previous.installerSha256 != target.installerSha256:
        raise ReleaseRejected(ReleaseFailure.SOURCE)
    return previous


def _registry_identity(path):
    if not path.exists() and not path.is_symlink():
        return 'absent'
    if path.resolve() != path or not path.is_file() or path.stat().st_size > 1048576:
        raise ReleaseRejected(ReleaseFailure.UPGRADE)
    return 'sha256:' + digest(path)


def _upgrade_observations(log):
    if log.stat().st_size > 4 * 1024 * 1024:
        raise ReleaseRejected(ReleaseFailure.UPGRADE)
    stages = {}
    for line in log.read_text().splitlines():
        if not line.startswith('{'):
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get('event') == 'kast_installation':
            if (set(event) != {'event', 'stage', 'outcome'} or event['outcome'] != 'COMPLETED'
                    or event['stage'] in stages):
                raise ReleaseRejected(ReleaseFailure.UPGRADE)
            stages[event['stage']] = event['outcome']
    if not {'PRIOR_ADMISSION', 'PRIOR_RETIREMENT', 'CONFIGURATION_VALIDATION', 'COMMAND_QUALIFICATION'} <= stages.keys():
        raise ReleaseRejected(ReleaseFailure.UPGRADE)


def prepare_release_upgrade(isolation, previous, target, idea):
    prior = install_release(isolation, previous, idea)
    previous_sessions = inspect_shell_sessions(isolation, prior, previous=True)
    prior_root = Path(prior.product)
    configuration = prior_root / 'config/environment'
    configuration_digest = digest(configuration)
    registry = _registry_identity(prior_root / 'config/workspaces.json')
    product_executable(prior_root, isolation.root)
    # Archive only paths just authored and admitted by this fixture; the next install selects the same owned root.
    for name in ('released-assets', 'released-install.private.log', 'released-product-admission.json'):
        source, destination = isolation.root / name, isolation.root / ('previous-' + name)
        if source.resolve() != source or destination.exists() or destination.is_symlink():
            raise ReleaseRejected(ReleaseFailure.OWNERSHIP)
        source.rename(destination)
    installed = install_release(isolation, target, idea)
    log = isolation.root / 'released-install.private.log'
    _upgrade_observations(log)
    if (digest(configuration) != configuration_digest
            or digest(prior_root / 'installation.json') != prior.installationManifestSha256
            or _registry_identity(Path(installed.product) / 'config/workspaces.json') != registry):
        raise ReleaseRejected(ReleaseFailure.UPGRADE)
    verify_control(prior_root, previous.control, json.loads((prior_root / 'installation.json').read_text()))
    target_sessions = inspect_shell_sessions(isolation, installed)
    return ReleasedUpgradeReceipt(prior, installed, target.commit, previous_sessions, target_sessions,
        configuration_digest, registry, str(log), digest(log))
