#!/usr/bin/env python3
"""Bounded installed catalog/default projection tests; no executable or IDE is launched."""
from dataclasses import asdict, dataclass, replace
import unittest

from released_acceptance_product import ReleaseRejected
from released_tool_inventory import OPERATIONS, admit_inventory


@dataclass(frozen=True)
class Tool:
    name: str
    operationId: str
    effect: str


@dataclass(frozen=True)
class Bootstrap:
    tools: tuple[Tool, ...]
    schemaVersion: int = 3


@dataclass(frozen=True)
class Projection:
    hostedBootstrap: Bootstrap
    schemaVersion: int = 17
    namespace: str = 'kast'


@dataclass(frozen=True)
class Schema:
    serverProjection: Projection
    schemaVersion: int = 1


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.tools = tuple(Tool(name, operation, 'intellij_read_and_persistence_write' if name == 'workspace_lifecycle'
                          else 'intellij_write' if name in {'add_declaration', 'replace_body'} else 'intellij_read')
                           for name, operation in OPERATIONS)
        self.defaults = tuple(name for name, _ in OPERATIONS)
        self.configuration = 'KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n'

    def schema(self, tools):
        return asdict(Schema(Projection(Bootstrap(tools))))

    def test_five_advertised_tools_and_defaults_are_distinct_from_two_explicit_reads(self):
        admitted = admit_inventory(self.schema(self.tools), self.configuration, 'a' * 64)
        self.assertEqual(len(admitted.advertisedTools), 5)
        self.assertEqual(len(admitted.configuredDefaultTools), 5)
        self.assertEqual(set(admitted.explicitReadTools), {'query_symbols', 'check_diagnostics'})

    def test_missing_tool_duplicate_name_and_changed_operation_are_refused(self):
        for tools in (self.tools[:-1], self.tools[:-1] + self.tools[:1],
                      (replace(self.tools[0], operationId='query.run'),) + self.tools[1:]):
            with self.subTest(tools=tools), self.assertRaises(ReleaseRejected):
                admit_inventory(self.schema(tools), self.configuration, 'a' * 64)

    def test_wrong_catalog_version_is_refused(self):
        document = self.schema(self.tools)
        document['schemaVersion'] = 2
        with self.assertRaises(ReleaseRejected):
            admit_inventory(document, self.configuration, 'a' * 64)
        document = self.schema(self.tools)
        document['serverProjection']['hostedBootstrap']['schemaVersion'] = 1
        with self.assertRaises(ReleaseRejected):
            admit_inventory(document, self.configuration, 'a' * 64)
        document = self.schema(self.tools)
        document['serverProjection']['schemaVersion'] = 16
        with self.assertRaises(ReleaseRejected):
            admit_inventory(document, self.configuration, 'a' * 64)

    def test_retired_tool_selection_is_refused(self):
        for config in ('KAST_APP_SERVER_TOOLS=query_symbols\n', 'KAST_APP_SERVER_TOOLS=\n'):
            with self.subTest(config=config), self.assertRaises(ReleaseRejected):
                admit_inventory(self.schema(self.tools), config, 'a' * 64)


if __name__ == '__main__':
    unittest.main()
