#!/usr/bin/env python3
"""Raw-tool helper orchestration only; responses below are typed offline fixtures."""
from dataclasses import asdict, dataclass, replace
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from hosted_raw_symbol_regression import run_raw_symbol_regression


@dataclass(frozen=True)
class Candidate:
    candidateSelector: str = 'candidate:v2:issued-proof'
    name: str = 'NativeChangeTarget'
    file: str = 'src/main/kotlin/Fixture.kt'
    kind: str = 'class'
    type: str = 'declaration'


@dataclass(frozen=True)
class Lookup:
    items: tuple[Candidate, ...] = (Candidate(),)
    operation: str = 'symbol.discover'
    status: str = 'complete'


@dataclass(frozen=True)
class Signature:
    qualifiedIdentity: str = 'fixture.NativeChangeTarget'
    type: str = 'class-like'


@dataclass(frozen=True)
class Compiler:
    identity: str = 'canonical-signature-sha256-v1|' + 'a' * 64
    signature: Signature = Signature()


@dataclass(frozen=True)
class Symbol:
    name: str = 'NativeChangeTarget'
    file: str = 'src/main/kotlin/Fixture.kt'
    qualifiedIdentity: str = 'fixture.NativeChangeTarget'
    selector: str = 'exact:v2:compiler-proof'
    compilerEvidence: Compiler = Compiler()


@dataclass(frozen=True)
class Inspect:
    symbol: Symbol = Symbol()
    operation: str = 'symbol.inspect'
    status: str = 'complete'


class RawSymbolTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).resolve() / 'workspace'
        self.workspace.mkdir()
        self.expected_file = str(self.workspace / 'src/main/kotlin/Fixture.kt')

    def replay(self, lookup=None):
        lookup = lookup if lookup is not None else Lookup(items=(Candidate(file=self.expected_file),))
        rows, calls, validations = [], [], []
        def invoke(surface, tool, request):
            calls.append((surface, tool, request))
            return asdict(lookup if tool == 'symbol_lookup' else Inspect(symbol=Symbol(file=self.expected_file)))
        def validate(tool, response):
            validations.append(tool)
            return 'a' * 64
        def record(name, tool, checks, count=0, response=None):
            if not all(type(value) is bool for value in checks.values()):
                raise AssertionError('receipt assertions require bool values')
            rows.append((name, checks))
        replay = SimpleNamespace(surface='cli', fixture=SimpleNamespace(workspace=self.workspace),
            transport=SimpleNamespace(invoke=invoke, validate=validate), record=record)
        run_raw_symbol_regression(replay)
        return rows, calls, validations

    def test_issued_candidate_is_forwarded_verbatim_and_both_outputs_validated(self):
        rows, calls, validations = self.replay()
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(all(checks.values()) for _, checks in rows))
        self.assertEqual(calls[1][2]['target']['selector'], Candidate().candidateSelector)
        self.assertEqual(validations, ['symbol_lookup', 'symbol_inspect'])

    def test_discovery_field_failures_are_distinct_and_do_not_disclose_private_identity(self):
        expected = Candidate(file=self.expected_file)
        for candidate, failed_check in (
                (replace(expected, file='src/main/kotlin/Fixture.kt'), 'exactAuthoredFile'),
                (replace(expected, file=str(self.workspace.parent / 'foreign/src/main/kotlin/Fixture.kt')), 'exactAuthoredFile'),
                (replace(expected, type='file'), 'declarationVariant'),
                (replace(expected, kind='symbol'), 'classKind'),
                (replace(expected, name='Other'), 'expectedName'),
                (replace(expected, candidateSelector=''), 'candidateAuthorityAvailable')):
            with self.subTest(check=failed_check):
                rows, calls, _ = self.replay(Lookup(items=(candidate,)))
                self.assertFalse(rows[0][1][failed_check])
                self.assertEqual(len(calls), 1)
                self.assertNotIn(self.expected_file, json.dumps(rows))
                self.assertNotIn(Candidate().candidateSelector, json.dumps(rows))

    def test_unavailable_candidate_records_failure_without_manufacturing_inspect_authority(self):
        rows, calls, validations = self.replay(Lookup(items=()))
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(rows), 2)
        self.assertFalse(all(rows[1][1].values()))
        self.assertEqual(validations, ['symbol_lookup'])


if __name__ == '__main__':
    unittest.main()
