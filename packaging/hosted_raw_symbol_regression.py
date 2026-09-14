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
    candidate = candidates[0] if len(candidates) == 1 else {}
    # Raw symbol documents retain CanonicalWorkspaceFilePath.stableValue verbatim: absolute, not query display paths.
    expected_file = str(replay.fixture.workspace / 'src/main/kotlin/Fixture.kt')
    checks = {
        'canonicalOperation': lookup.get('operation') == 'symbol.discover',
        'complete': lookup.get('status') == 'complete',
        'oneCandidate': len(candidates) == 1,
        'declarationVariant': candidate.get('type') == 'declaration',
        'classKind': candidate.get('kind') == 'class',
        'expectedName': candidate.get('name') == 'NativeChangeTarget',
        'exactAuthoredFile': candidate.get('file') == expected_file,
        'candidateAuthorityAvailable': isinstance(candidate.get('candidateSelector'), str)
                                       and bool(candidate.get('candidateSelector')),
        'outputSchema': isinstance(lookup_schema, str) and bool(lookup_schema),
    }
    replay.record('explicit-raw-symbol-lookup', 'symbol_lookup', checks, len(candidates), lookup)
    if not all(checks.values()):
        replay.record('explicit-raw-symbol-inspect', 'symbol_inspect', {'candidateAvailable': False})
        return
    inspected = replay.transport.invoke(replay.surface, 'symbol_inspect',
        asdict(InspectRequest(CandidateTarget(candidate['candidateSelector']))))
    inspect_schema = replay.transport.validate('symbol_inspect', inspected)
    symbol = inspected.get('symbol', {})
    compiler = symbol.get('compilerEvidence', {})
    replay.record('explicit-raw-symbol-inspect', 'symbol_inspect',
        {'canonicalOperation': inspected.get('operation') == 'symbol.inspect',
         'complete': inspected.get('status') == 'complete',
         'expectedName': symbol.get('name') == candidate['name'],
         'sameCandidateFile': symbol.get('file') == candidate['file'],
         'exactAuthoredFile': symbol.get('file') == expected_file,
         'qualifiedClassIdentity': symbol.get('qualifiedIdentity') == 'fixture.NativeChangeTarget',
         'classKind': symbol.get('kind') == 'classlike',
         'exactSelectorAvailable': isinstance(symbol.get('selector'), str) and bool(symbol.get('selector')),
         'compilerIdentityAvailable': isinstance(compiler.get('identity'), str)
             and re.fullmatch(r'canonical-signature-sha256-v1\|[0-9a-f]{64}', compiler['identity']) is not None,
         'compilerQualifiedIdentity': compiler.get('signature', {}).get('qualifiedIdentity') == 'fixture.NativeChangeTarget',
         'classSignature': compiler.get('signature', {}).get('type') == 'class-like',
         'outputSchema': isinstance(inspect_schema, str) and bool(inspect_schema)}, 1, inspected)
