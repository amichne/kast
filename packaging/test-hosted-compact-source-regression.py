#!/usr/bin/env python3
"""Compact declaration target uses its concrete DTO type, not a redundant discriminator."""
from dataclasses import asdict, dataclass, field
import unittest
from hosted_compact_source_regression import _rows

@dataclass(frozen=True)
class Range:
    startInclusive: int = 0
    endExclusive: int = 8

@dataclass(frozen=True)
class Selection:
    id: int = 0
    range: Range = field(default_factory=Range)

@dataclass(frozen=True)
class Entry:
    selector: str = 'fixture-source-selection'

@dataclass(frozen=True)
class Candidate:
    selector: str = 'fixture-candidate'

@dataclass(frozen=True)
class Declaration:
    type: str = 'declaration'
    kind: str = 'function'
    name: str = 'fixture'
    visibility: str = 'public'
    nestingDepth: int = 0
    parent: int = 0
    selection: Selection = field(default_factory=Selection)
    target: Candidate = field(default_factory=Candidate)

class CompactOracleTest(unittest.TestCase):
    def test_concrete_candidate_target_has_no_redundant_type_field(self):
        row, = _rows([asdict(Declaration())], [asdict(Entry())])
        self.assertEqual(('candidate', 'fixture-candidate', None), row[-1])

if __name__ == '__main__':
    unittest.main()
