"""Qualify the admitted release's complete wrappers against one explicit Codex authority."""
from dataclasses import dataclass
import json
import os
from pathlib import Path
import subprocess

from acceptance_idea import digest
from installed_codex_lifecycle import AcceptanceFailure, HostLifecycleReceipt, qualify_installed_lifecycle
from released_acceptance_product import ReleaseFailure, ReleaseRejected, product_executable


@dataclass(frozen=True)
class ReleasedCoordinatorReceipt:
    codexVersion: str
    codexExecutableSha256: str
    controlWrapperSha256: str
    facadeWrapperSha256: str
    installedSchemaSha256: str
    configuredTools: tuple[str, ...]
    lifecycle: HostLifecycleReceipt


def admitted_facade(product):
    facade = product / 'bin/kast-codex-complete'
    if facade.resolve() != facade or not facade.is_file() or not os.access(facade, os.X_OK):
        raise ReleaseRejected(ReleaseFailure.PAYLOAD)
    manifest = json.loads((product / 'installation.json').read_text())
    entries = [item for item in manifest['payloadFiles'] if item['path'] == 'bin/kast-codex-complete']
    if len(entries) != 1 or entries[0]['sha256'] != 'sha256:' + digest(facade):
        raise ReleaseRejected(ReleaseFailure.PAYLOAD)
    return facade


def qualify_released_coordinator(isolation, installed, inventory, fixture):
    product = Path(installed.product)
    control = product_executable(product, isolation.root)
    facade = admitted_facade(product)
    codex = isolation.tools['codex']
    environment = dict(fixture.environment)
    environment.update(KAST_REAL_CODEX_EXECUTABLE=str(codex), CODEX_EXECUTABLE=str(codex),
        KAST_ENABLE_APP_SERVER='1', KAST_APP_SERVER_PUBLIC_ENDPOINT='private', KAST_APP_SERVER_TOOLS=','.join(inventory.configuredDefaultTools))
    try:
        version = subprocess.run([str(codex), '--version'], cwd=fixture.workspace, env=environment,
                                 check=True, capture_output=True, text=True, timeout=10).stdout.strip()
        if not version.startswith('codex-cli ') or len(version) > 128:
            raise AcceptanceFailure('Codex version authority rejected')
        receipt = qualify_installed_lifecycle(isolation, control, facade, environment,
            Path(environment['HOME']), fixture.workspace, product)
    except (AcceptanceFailure, subprocess.SubprocessError, OSError) as error:
        raise ReleaseRejected(ReleaseFailure.COORDINATOR) from error
    return ReleasedCoordinatorReceipt(version, digest(codex), digest(control), digest(facade),
        inventory.installedSchemaSha256, inventory.configuredDefaultTools, receipt)


def coordinator_qualified(receipt):
    """A release cannot inherit source-mode qualification without the complete lifecycle witness."""
    lifecycle = receipt.get('lifecycle', {})
    return (all(lifecycle.get(name) == 'VALIDATED' for name in
                ('initialize', 'threadStart', 'parentClosure', 'serviceDisable'))
            and lifecycle.get('beforeAttachment', {}).get('phase') == 'pending'
            and lifecycle.get('afterDetach', {}).get('phase') == 'prepared'
            and all(lifecycle.get(name, {}).get('publicSocketAndOwnership') == 'VALIDATED'
                    for name in ('beforeAttachment', 'afterDetach')))
