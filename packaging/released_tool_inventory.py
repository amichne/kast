"""Bind the installed advertised catalog and saved default selection to acceptance evidence."""
from dataclasses import dataclass
import json
import subprocess

from acceptance_idea import digest
from released_acceptance_product import product_executable, ReleaseFailure, ReleaseRejected


# Current public spellings only. Each remains bound to the installed canonical operation identifier.
OPERATIONS = (
    ('search_classes', 'query.run'), ('search_functions', 'query.run'),
    ('search_declarations', 'query.run'), ('query_symbols', 'query.run'),
    ('symbol_lookup', 'symbol.discover'), ('symbol_inspect', 'symbol.inspect'),
    ('source_read', 'source.read'), ('semantic_query', 'relation.read'), ('impact_analyze', 'traversal.run'),
    ('check_diagnostics', 'diagnostic.check'), ('change_plan', 'change.plan'),
    ('change_apply', 'change.apply'), ('change_recover', 'change.recover'),
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
    if (len(tools) != len(expected) or len(cli) != len(expected) or set(names) != expected.keys()
            or any(tool['operationId'] != expected[tool['name']] for tool in tools)
            or {item['toolName']: item['operationId'] for item in cli} != expected):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    selections = [line.split('=', 1)[1] for line in configuration.splitlines() if line.startswith('KAST_APP_SERVER_TOOLS=')]
    expected_defaults = tuple(name for name, _ in OPERATIONS if name not in ('symbol_lookup', 'symbol_inspect'))
    if len(selections) != 1 or tuple(selections[0].split(',')) != expected_defaults:
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    reads = tuple(tool['name'] for tool in tools if tool['effect'] in ('none', 'intellij_read')
                  and tool['operationId'] != 'change.plan')
    if len(reads) != 10 or not {'symbol_lookup', 'symbol_inspect'} <= set(reads):
        raise ReleaseRejected(ReleaseFailure.INVENTORY)
    return ReleasedToolInventory(names, expected_defaults, reads, schema_digest)


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
