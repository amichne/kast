"""Explicit opt-in native cases for the two catalog tools omitted from provider defaults."""
from dataclasses import asdict, dataclass
import re


@dataclass(frozen=True)
class NameTarget:
    query: str = 'NativeChangeTarget'
    kind: str = 'class'
    match: str = 'exact-name'
    type: str = 'name'


@dataclass(frozen=True)
class DiscoverRequest:
    target: NameTarget = NameTarget()
    limit: int = 10


@dataclass(frozen=True)
class CandidateTarget:
    selector: str
    type: str = 'candidate'


@dataclass(frozen=True)
class InspectRequest:
    target: CandidateTarget


def run_raw_symbol_regression(replay):
    lookup = replay.transport.invoke(replay.surface, 'symbol_lookup', asdict(DiscoverRequest()))
    lookup_schema = replay.transport.validate('symbol_lookup', lookup)
    candidates = lookup.get('items', [])
    valid = (lookup.get('operation') == 'symbol.discover' and lookup.get('status') == 'complete'
             and len(candidates) == 1 and candidates[0].get('name') == 'NativeChangeTarget'
             and candidates[0].get('type') == 'declaration' and candidates[0].get('kind') == 'class'
             and candidates[0].get('file') == 'src/main/kotlin/Fixture.kt'
             and isinstance(candidates[0].get('candidateSelector'), str)
             and bool(candidates[0]['candidateSelector']))
    replay.record('explicit-raw-symbol-lookup', 'symbol_lookup',
                  {'candidateFromAuthoredFixture': valid,
                   'outputSchema': isinstance(lookup_schema, str) and bool(lookup_schema)}, len(candidates), lookup)
    if not valid:
        replay.record('explicit-raw-symbol-inspect', 'symbol_inspect', {'candidateAvailable': False})
        return
    inspected = replay.transport.invoke(replay.surface, 'symbol_inspect',
        asdict(InspectRequest(CandidateTarget(candidates[0]['candidateSelector']))))
    inspect_schema = replay.transport.validate('symbol_inspect', inspected)
    symbol = inspected.get('symbol', {})
    compiler = symbol.get('compilerEvidence', {})
    replay.record('explicit-raw-symbol-inspect', 'symbol_inspect',
        {'candidateRefined': inspected.get('operation') == 'symbol.inspect' and inspected.get('status') == 'complete',
         'sameDeclaration': symbol.get('name') == candidates[0]['name'] and symbol.get('file') == candidates[0]['file']
                            and symbol.get('qualifiedIdentity') == 'fixture.NativeChangeTarget',
         'compilerIdentityAvailable': isinstance(symbol.get('selector'), str) and bool(symbol.get('selector'))
                                      and isinstance(compiler.get('identity'), str)
                                      and re.fullmatch(r'canonical-signature-sha256-v1\|[0-9a-f]{64}', compiler['identity']) is not None
                                      and compiler.get('signature', {}).get('qualifiedIdentity') == 'fixture.NativeChangeTarget'
                                      and compiler.get('signature', {}).get('type') == 'class-like',
         'outputSchema': isinstance(inspect_schema, str) and bool(inspect_schema)}, 1, inspected)
