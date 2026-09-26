"""Bind the installed advertised catalog and complete installed suite to acceptance evidence."""
from dataclasses import dataclass
import json
import subprocess

from acceptance_idea import digest
from released_acceptance_product import product_executable, ReleaseFailure, ReleaseRejected


# Current public spellings only. Each remains bound to the installed canonical operation identifier.
OPERATIONS = (
    ('workspace_lifecycle', 'workspace.lifecycle'),
    ('query_symbols', 'query.run'),
    ('source_read', 'source.read'),
    ('check_diagnostics', 'diagnostic.check'), ('change', 'change.run'),
)


@dataclass(frozen=True)
class ReleasedToolInventory:
    advertisedTools: tuple[str, ...]
    configuredDefaultTools: tuple[str, ...]
    explicitReadTools: tuple[str, ...]
    installedSchemaSha256: str


def admit_inventory(document, configuration, schema_digest):
    tools = document['serverProjection']['hostedBootstrap']['tools']
    cli = document['serverProjection']['cliInvocations']['operations']
    expected = dict(OPERATIONS)
    names = tuple(tool['name'] for tool in tools)
    cli_expected = {name: operation for name, operation in expected.items() if name != 'workspace_lifecycle'}
    if (len(tools) != len(expected) or len(cli) != len(cli_expected) or set(names) != expected.keys()
            or any(tool['operationId'] != expected[tool['name']] for tool in tools)
            or {item['toolName']: item['operationId'] for item in cli} != cli_expected):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    if any(line.startswith('KAST_APP_SERVER_TOOLS=') for line in configuration.splitlines()):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    reads = tuple(tool['name'] for tool in tools if tool['effect'] == 'intellij_read'
                  and tool['operationId'] != 'workspace.lifecycle')
    if len(reads) != 3 or set(reads) != {'query_symbols', 'source_read', 'check_diagnostics'}:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    return ReleasedToolInventory(names, names, reads, schema_digest)


def inspect_installed_inventory(isolation, product):
    executable = product_executable(product, isolation.root)
    configuration = product / 'config/environment'
    if configuration.resolve() != configuration or configuration.stat().st_size > 65536:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    schema = isolation.root / 'released-schema.private.json'
    with schema.open('xb') as output:
        schema.chmod(0o600)
        result = subprocess.run([str(executable), '--schema'], cwd=isolation.root / 'workspace', env=isolation.environment,
                                stdout=output, stderr=subprocess.PIPE, timeout=30)
    if result.returncode != 0 or not 0 < schema.stat().st_size <= 4 * 1024 * 1024:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    return admit_inventory(json.loads(schema.read_text()), configuration.read_text(), digest(schema))
