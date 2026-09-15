#!/usr/bin/env python3
"""Compatibility oracle detects changed facts, operation identity, authority and output schema."""
from dataclasses import asdict, dataclass, field, replace
from types import SimpleNamespace
import unittest

from hosted_read_name_regression import run_read_name_regression


@dataclass(frozen=True)
class RelationFact:
    occurrence: str = 'private-occurrence'
    proof: str = 'compiler-confirmed'


@dataclass(frozen=True)
class Proof:
    id: str = 'p'
    identity: str = 'private-proof'


@dataclass(frozen=True)
class Node:
    id: str
    name: str
    proof: str = 'p'


@dataclass(frozen=True)
class Edge:
    source: str = 's'
    target: str = 't'
    occurrence: str = 'private-occurrence'


@dataclass(frozen=True)
class Graph:
    nodes: tuple[Node, ...] = (Node('s', 'caller'), Node('t', 'helper'))
    proofs: tuple[Proof, ...] = (Proof(),)
    edges: tuple[Edge, ...] = (Edge(),)


@dataclass(frozen=True)
class Relation:
    operation: str = 'relation.read'
    status: str = 'complete'
    live: str = 'private-live'
    relations: tuple[RelationFact, ...] = (RelationFact(),)


@dataclass(frozen=True)
class Traversal:
    operation: str = 'traversal.run'
    status: str = 'complete'
    live: str = 'private-live'
    graph: Graph = field(default_factory=Graph)


class ReadNameRegressionTest(unittest.TestCase):
    def replay(self, change=lambda _tool, value: value, schema=lambda _tool: 'same-schema', surface='provider'):
        calls, rows = [], []

        def invoke(invoked_surface, tool, request):
            calls.append((invoked_surface, tool, request))
            value = Relation() if tool in ('read_relations', 'semantic_query') else Traversal()
            return asdict(change(tool, value)), schema(tool)

        replay = SimpleNamespace(surface=surface, live='private-live',
            seeds={'helper': {'ref': 'private-reference'}},
            transport=SimpleNamespace(invoke_observed=invoke),
            record=lambda _name, _tool, checks, _count, _response: rows.append(checks))
        run_read_name_regression(replay)
        return calls, rows

    def test_original_and_preferred_inputs_share_request_bytes_and_schema(self):
        calls, rows = self.replay()
        self.assertEqual(['read_relations', 'semantic_query', 'traverse_relations', 'impact_analyze'],
                         [call[1] for call in calls])
        self.assertEqual(calls[0][2], calls[1][2])
        self.assertEqual(calls[2][2], calls[3][2])
        self.assertEqual(2, len(rows))
        self.assertTrue(all(all(row.values()) for row in rows))

    def test_legacy_fact_operation_authority_and_schema_drift_fail(self):
        for field, value in (('operation', 'wrong.operation'), ('live', 'other-live'),
                             ('status', 'qualified'), ('relations', (RelationFact('changed'),))):
            with self.subTest(field=field):
                _, rows = self.replay(lambda tool, result: replace(result, **{field: value})
                    if tool == 'semantic_query' else result)
                self.assertFalse(all(rows[0].values()))
        _, rows = self.replay(schema=lambda tool: 'changed' if tool == 'impact_analyze' else 'same-schema')
        self.assertFalse(all(rows[1].values()))

    def test_cli_command_identity_needs_no_alias_surface(self):
        self.assertEqual(([], []), self.replay(surface='cli'))


if __name__ == '__main__':
    unittest.main()
