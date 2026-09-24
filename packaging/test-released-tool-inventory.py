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
class Invocation:
    toolName: str
    operationId: str


@dataclass(frozen=True)
class Bootstrap:
    tools: tuple[Tool, ...]


@dataclass(frozen=True)
class Invocations:
    operations: tuple[Invocation, ...]


@dataclass(frozen=True)
class Projection:
    hostedBootstrap: Bootstrap
    cliInvocations: Invocations


@dataclass(frozen=True)
class Schema:
    serverProjection: Projection


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.tools = tuple(Tool(name, operation, 'intellij_write' if name == 'change' else 'intellij_read')
                           for name, operation in OPERATIONS)
        self.cli = Invocations(tuple(Invocation(name, operation) for name, operation in OPERATIONS
                                     if name not in ('workspace_lifecycle', 'change')))
        self.defaults = tuple(name for name, _ in OPERATIONS)
        self.configuration = 'KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n'

    def schema(self, tools):
        return asdict(Schema(Projection(Bootstrap(tools), self.cli)))

    def test_all_twelve_advertised_tools_and_defaults_are_distinct_from_ten_explicit_reads(self):
        admitted = admit_inventory(self.schema(self.tools), self.configuration, 'a' * 64)
        self.assertEqual(len(admitted.advertisedTools), 12)
        self.assertEqual(len(admitted.configuredDefaultTools), 12)
        self.assertEqual(len(admitted.explicitReadTools), 10)
        self.assertIn('symbol_lookup', admitted.explicitReadTools)
        self.assertIn('symbol_lookup', admitted.configuredDefaultTools)

    def test_missing_tool_duplicate_name_and_changed_operation_are_refused(self):
        for tools in (self.tools[:-1], self.tools[:-1] + self.tools[:1],
                      (replace(self.tools[0], operationId='symbol.discover'),) + self.tools[1:]):
            with self.subTest(tools=tools), self.assertRaises(ReleaseRejected):
                admit_inventory(self.schema(tools), self.configuration, 'a' * 64)

    def test_workspace_tool_has_no_cli_invocation(self):
        self.cli = Invocations(self.cli.operations + (Invocation('workspace_lifecycle', 'workspace.lifecycle'),))
        with self.assertRaises(ReleaseRejected):
            admit_inventory(self.schema(self.tools), self.configuration, 'a' * 64)

    def test_change_tool_has_no_cli_invocation(self):
        self.cli = Invocations(self.cli.operations + (Invocation('change', 'change.run'),))
        with self.assertRaises(ReleaseRejected):
            admit_inventory(self.schema(self.tools), self.configuration, 'a' * 64)

    def test_retired_tool_selection_is_refused(self):
        for config in ('KAST_APP_SERVER_TOOLS=symbol_lookup\n', 'KAST_APP_SERVER_TOOLS=\n'):
            with self.subTest(config=config), self.assertRaises(ReleaseRejected):
                admit_inventory(self.schema(self.tools), config, 'a' * 64)


if __name__ == '__main__':
    unittest.main()
