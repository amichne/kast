"""Bind the installed advertised catalog and complete installed suite to acceptance evidence."""
from dataclasses import dataclass
import json

from acceptance_idea import digest
from released_acceptance_product import product_executable, ReleaseFailure, ReleaseRejected


# Current public spellings only. Each remains bound to the installed canonical operation identifier.
OPERATIONS = (
    ('workspace_lifecycle', 'workspace.lifecycle'),
    ('query_symbols', 'query.run'),
    ('read_source', 'source.read'),
    ('check_diagnostics', 'diagnostic.check'), ('add_declaration', 'change.run'),
)


@dataclass(frozen=True)
class ReleasedToolInventory:
    advertisedTools: tuple[str, ...]
    configuredDefaultTools: tuple[str, ...]
    explicitReadTools: tuple[str, ...]
    installedCatalogSha256: str


def admit_inventory(document, configuration, catalog_digest):
    expected = dict(OPERATIONS)
    try:
        projection = document['serverProjection']
        tools = projection['hostedBootstrap']['tools']
        names = tuple(tool['name'] for tool in tools)
        valid = (document['schemaVersion'] == 1 and projection['schemaVersion'] == 16
                 and projection['hostedBootstrap']['schemaVersion'] == 2
                 and projection['namespace'] == 'kast' and len(tools) == len(expected)
                 and set(names) == expected.keys()
                 and all(tool['operationId'] == expected[tool['name']] for tool in tools))
    except (KeyError, TypeError, ValueError):
        valid = False
    if not valid:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    if any(line.startswith('KAST_APP_SERVER_TOOLS=') for line in configuration.splitlines()):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    try:
        effects = {tool['name']: tool['effect'] for tool in tools}
    except (KeyError, TypeError):
        raise ReleaseRejected(ReleaseFailure.INVENTORY) from None
    if (effects.get('workspace_lifecycle') != 'intellij_read_and_persistence_write'
            or effects.get('add_declaration') != 'intellij_write'):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    reads = tuple(name for name in names if effects[name] == 'intellij_read')
    if len(reads) != 3 or set(reads) != {'query_symbols', 'read_source', 'check_diagnostics'}:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    return ReleasedToolInventory(names, names, reads, catalog_digest)


def inspect_installed_inventory(isolation, product):
    product_executable(product, isolation.root)
    configuration = product / 'config/environment'
    if configuration.resolve() != configuration or configuration.stat().st_size > 65536:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    catalog = product / 'share/kast/provider-catalog.json'
    if (catalog.resolve() != catalog or not catalog.is_file() or catalog.is_symlink()
            or not 0 < catalog.stat().st_size <= 4 * 1024 * 1024):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    manifest = json.loads((product / 'installation.json').read_text())
    entries = [entry for entry in manifest['payloadFiles'] if entry['path'] == 'share/kast/provider-catalog.json']
    if len(entries) != 1 or entries[0]['sha256'] != 'sha256:' + digest(catalog):
        raise ReleaseRejected(ReleaseFailure.PAYLOAD)
    return admit_inventory(json.loads(catalog.read_text()), configuration.read_text(), digest(catalog))
