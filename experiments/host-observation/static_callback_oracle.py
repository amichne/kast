"""Source-authored oracle for the bounded native static callback acceptance.

This module never reads a semantic response. Exact declarations and expressions
are authored below and located in the three fixture sources. It is not a Kotlin
parser or a second query engine. Missing, changed, or duplicate authored text is
a fixture error. ``load_oracle`` is the only filesystem boundary; ``materialize``
can be checked with immutable source documents.
"""

from dataclasses import dataclass, field
from enum import Enum
from hashlib import sha256
from pathlib import Path


class FixtureIntegrityError(ValueError):
    """The authored fixture no longer supplies the oracle's starting facts."""


class GraphRejectionType(str, Enum):
    UNRESOLVED = 'UNRESOLVED'
    CALLABLE_VALUE_UNPROVEN = 'CALLABLE_VALUE_UNPROVEN'


class FlowObligation(str, Enum):
    EXTERNAL_CALLABLE = 'EXTERNAL_CALLABLE'
    CALLBACK_CYCLE = 'CALLBACK_CYCLE'
    NO_INVOCATION_PROVEN = 'NO_INVOCATION_PROVEN'
    RESULT_LIMIT_REACHED = 'RESULT_LIMIT_REACHED'


class InvocationScan(str, Enum):
    INCOMPLETE = 'INCOMPLETE'


@dataclass(frozen=True)
class Utf16Range:
    startInclusive: int
    endExclusive: int

    def __post_init__(self):
        if self.startInclusive < 0 or self.endExclusive <= self.startInclusive:
            raise FixtureIntegrityError('source range must be nonempty and nonnegative')


@dataclass(frozen=True)
class SourceSpan:
    file: str
    range: Utf16Range
    text: str


@dataclass(frozen=True)
class SourceDocument:
    relative_path: str
    text: str


@dataclass(frozen=True)
class SymbolOracle:
    name: str
    fqn: str
    declaration: SourceSpan
    name_site: SourceSpan


@dataclass(frozen=True)
class FormalOracle:
    callable: SymbolOracle
    parameter: SourceSpan
    position: int


@dataclass(frozen=True)
class SupplyOracle:
    supplier: SymbolOracle
    target: SymbolOracle
    body: SourceSpan
    target_occurrences: tuple[SourceSpan, ...]
    call: SourceSpan
    formal: FormalOracle


@dataclass(frozen=True)
class ForwardingOracle:
    source: FormalOracle
    target: FormalOracle
    argument: SourceSpan
    call: SourceSpan


@dataclass(frozen=True)
class InvocationOracle:
    owner: SymbolOracle
    occurrence: SourceSpan


@dataclass(frozen=True)
class UnresolvedRejection:
    obligations: tuple[FlowObligation, ...]
    scan: InvocationScan = InvocationScan.INCOMPLETE
    type: GraphRejectionType = GraphRejectionType.UNRESOLVED


@dataclass(frozen=True)
class CallableValueRejection:
    type: GraphRejectionType = GraphRejectionType.CALLABLE_VALUE_UNPROVEN


RejectionOracle = UnresolvedRejection | CallableValueRejection


@dataclass(frozen=True)
class CompleteCoverageOracle:
    type: str = field(default='COMPLETE', init=False)


@dataclass(frozen=True)
class CompleteCase:
    name: str
    supplies: tuple[SupplyOracle, ...]
    forwardings: tuple[ForwardingOracle, ...]
    invocation: InvocationOracle
    forbidden_targets: tuple[SymbolOracle, ...]

    def __post_init__(self):
        _admit_same_root_supplies(self.supplies)

    @property
    def supply(self) -> SupplyOracle:
        """The root's first supply; callers checking inventory must use ``supplies``."""
        return self.supplies[0]


@dataclass(frozen=True)
class RejectedLambdaCase:
    name: str
    supply: SupplyOracle
    forwardings: tuple[ForwardingOracle, ...]
    rejection: UnresolvedRejection
    cause_site: SourceSpan

    @property
    def supplies(self) -> tuple[SupplyOracle, ...]:
        return (self.supply,)


@dataclass(frozen=True)
class ExcludedReferenceCase:
    """Named calls exclude this reference; no callback transfer proof is asserted."""
    name: str
    supplier: SymbolOracle
    target: SymbolOracle
    call: SourceSpan
    reference: SourceSpan
    target_occurrence: SourceSpan


@dataclass(frozen=True)
class RejectedFormalCase:
    name: str
    owner: SymbolOracle
    formal: FormalOracle
    invocation: InvocationOracle
    rejection: CallableValueRejection


@dataclass(frozen=True)
class RejectedResourceCase:
    """The same alpha routes at a result grant that cannot retain their witnesses."""
    name: str
    supplies: tuple[SupplyOracle, ...]
    rejection: UnresolvedRejection
    max_results: int
    cause_site: SourceSpan
    original_coverage: CompleteCoverageOracle = CompleteCoverageOracle()

    def __post_init__(self):
        _admit_same_root_supplies(self.supplies)

    @property
    def supply(self) -> SupplyOracle:
        return self.supplies[0]


CaseOracle = CompleteCase | RejectedLambdaCase | RejectedFormalCase | ExcludedReferenceCase


@dataclass(frozen=True)
class SuiteOracle:
    root: str
    source_sha256: str
    symbols: tuple[SymbolOracle, ...]
    complete_cases: tuple[CompleteCase, ...]
    rejected_cases: tuple[RejectedLambdaCase | RejectedFormalCase, ...]
    excluded_cases: tuple[ExcludedReferenceCase, ...]
    resource_cases: tuple[RejectedResourceCase, ...]

    @property
    def cases(self) -> tuple[CaseOracle, ...]:
        """Cases for admissible complete grants; resource controls own a fixed grant."""
        return self.complete_cases + self.rejected_cases + self.excluded_cases


SUPPLIERS_FILE = 'suppliers/src/main/kotlin/fixture/staticcallbacks/suppliers/Suppliers.kt'
FORWARDING_FILE = 'forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt'
INVOCATION_FILE = 'invocation/src/main/kotlin/fixture/staticcallbacks/invocation/Invocation.kt'
SOURCE_FILES = (SUPPLIERS_FILE, FORWARDING_FILE, INVOCATION_FILE)


def _admit_same_root_supplies(supplies: tuple[SupplyOracle, ...]) -> None:
    if not supplies or any(supply.supplier != supplies[0].supplier for supply in supplies):
        raise FixtureIntegrityError('case supplies must be nonempty and belong to one exact named root')


def _utf16_length(text: str) -> int:
    return len(text.encode('utf-16-le')) // 2


def _unique_start(text: str, expression: str) -> int:
    if text.count(expression) != 1:
        raise FixtureIntegrityError(f'authored expression must occur exactly once: {expression}')
    return text.index(expression)


def _span(root: str, source: SourceDocument, expression: str, region: str | None = None) -> SourceSpan:
    base = 0 if region is None else _unique_start(source.text, region)
    region_text = source.text if region is None else region
    start = base + _unique_start(region_text, expression)
    begin = _utf16_length(source.text[:start])
    return SourceSpan(str(Path(root) / source.relative_path), Utf16Range(begin, begin + _utf16_length(expression)), expression)


def _occurrences(root: str, source: SourceDocument, expression: str, region: str, count: int) -> tuple[SourceSpan, ...]:
    base = _unique_start(source.text, region)
    if region.count(expression) != count:
        raise FixtureIntegrityError('authored target occurrence count changed')
    positions, start = [], 0
    for _ in range(count):
        start = region.index(expression, start)
        begin = _utf16_length(source.text[:base + start])
        positions.append(SourceSpan(str(Path(root) / source.relative_path),
                                    Utf16Range(begin, begin + _utf16_length(expression)), expression))
        start += len(expression)
    return tuple(positions)


def materialize(root: str, sources: tuple[SourceDocument, ...]) -> SuiteOracle:
    """Refine authored text into exact source expectations without consulting results."""
    if not Path(root).is_absolute():
        raise FixtureIntegrityError('fixture root must be absolute')
    if tuple(source.relative_path for source in sources) != SOURCE_FILES:
        raise FixtureIntegrityError('the exact ordered three-source inventory is required')
    suppliers, forwarding, invocation = sources
    declarations: tuple[tuple[SourceDocument, str, str], ...] = (
        (suppliers, 'alphaEntry',
         '// The suppliers share the formal route, but each owns a distinct callback body.\n'
         'fun alphaEntry(): String {\n'
         '    sharedWrapper { alphaSink() }\n'
         '    return sharedWrapper { alphaSink(); "alpha" }\n'
         '}'),
        (suppliers, 'betaEntry', 'fun betaEntry(): String = sharedWrapper { betaSink() }'),
        (suppliers, 'callableReferenceEntry',
         '// Callable references remain outside the admitted COMPLETE_ONLY callback model.\n'
         'fun callableReferenceEntry(): String = sharedWrapper(::referenceSink)'),
        (suppliers, 'recursiveEntry',
         '// Static negative controls: never execute these functions to qualify a query.\n'
         'fun recursiveEntry(): String = recursiveWrapper { recursiveSink() }'),
        (suppliers, 'externalEscapeEntry', 'fun externalEscapeEntry(): Int = externalEscapeWrapper { escapeSink() }'),
        (forwarding, 'sharedWrapper', 'fun sharedWrapper(block: () -> String): String = forwardOnce(block)'),
        (forwarding, 'forwardOnce', 'fun forwardOnce(block: () -> String): String = invokeCallback(block)'),
        (forwarding, 'recursiveWrapper', 'fun recursiveWrapper(block: () -> String): String = recursiveWrapper(block)'),
        (forwarding, 'externalEscapeWrapper', 'fun externalEscapeWrapper(block: () -> String): Int = externalEscape(block)'),
        (invocation, 'invokeCallback', 'fun invokeCallback(block: () -> String): String = block()'),
        (invocation, 'externalEscape', 'fun externalEscape(block: () -> String): Int = java.util.Collections.singletonList(block).size'),
        (invocation, 'alphaSink', 'fun alphaSink(): String = "alpha"'),
        (invocation, 'betaSink', 'fun betaSink(): String = "beta"'),
        (invocation, 'referenceSink', 'fun referenceSink(): String = "reference"'),
        (invocation, 'recursiveSink', 'fun recursiveSink(): String = "recursive"'),
        (invocation, 'escapeSink', 'fun escapeSink(): String = "escape"'),
    )

    def symbol(source: SourceDocument, name: str, declaration: str) -> SymbolOracle:
        package = source.text.splitlines()[0].removeprefix('package ')
        if package != 'fixture.staticcallbacks.' + source.relative_path.split('/')[0]:
            raise FixtureIntegrityError('fixture package must match its owning module')
        # Kast preserves the native KtNamedDeclaration.textRange, including its
        # attached leading comment. These three envelopes are authored explicitly
        # above; the oracle does not guess comment ownership or trim the proof.
        # The query seed selects the name token inside that full PSI envelope.
        name_start = _unique_start(source.text, 'fun ' + name + '(') + len('fun ')
        offset = _utf16_length(source.text[:name_start])
        name_site = SourceSpan(str(Path(root) / source.relative_path), Utf16Range(offset, offset + len(name)), name)
        return SymbolOracle(name, package + '.' + name, _span(root, source, declaration), name_site)

    symbols = tuple(symbol(*declaration) for declaration in declarations)

    def named(name: str) -> SymbolOracle:
        return next(value for value in symbols if value.name == name)

    def source_for(value: SymbolOracle) -> SourceDocument:
        return next(source for source in sources if str(Path(root) / source.relative_path) == value.declaration.file)

    def within(value: SymbolOracle, text: str) -> SourceSpan:
        return _span(root, source_for(value), text, value.declaration.text)

    def formal(name: str) -> FormalOracle:
        value = named(name)
        return FormalOracle(value, within(value, 'block: () -> String'), 0)

    def supply(entry: str, sink: str, receiver: str, body: str) -> SupplyOracle:
        supplier, target = named(entry), named(sink)
        targets = _occurrences(root, source_for(supplier), sink, body, 1)
        return SupplyOracle(supplier, target, within(supplier, body), targets,
                            within(supplier, receiver + ' ' + body), formal(receiver))

    def forward(source: str, target: str) -> ForwardingOracle:
        origin, destination = formal(source), formal(target)
        call = target + '(block)'
        site = within(origin.callable, call)
        argument_start = site.range.startInclusive + len(target) + 1
        argument = SourceSpan(site.file, Utf16Range(argument_start, argument_start + len('block')), 'block')
        return ForwardingOracle(origin, destination, argument, site)

    shared_route = (forward('sharedWrapper', 'forwardOnce'), forward('forwardOnce', 'invokeCallback'))
    terminal = InvocationOracle(named('invokeCallback'), within(named('invokeCallback'), 'block()'))
    alpha = CompleteCase('alpha', (
        supply('alphaEntry', 'alphaSink', 'sharedWrapper', '{ alphaSink() }'),
        supply('alphaEntry', 'alphaSink', 'sharedWrapper', '{ alphaSink(); "alpha" }'),
    ), shared_route, terminal,
                         (named('betaSink'), named('referenceSink')))
    beta = CompleteCase('beta', (supply('betaEntry', 'betaSink', 'sharedWrapper', '{ betaSink() }'),),
                        shared_route, terminal,
                        (named('alphaSink'), named('referenceSink')))
    recursive = RejectedLambdaCase(
        'recursive', supply('recursiveEntry', 'recursiveSink', 'recursiveWrapper', '{ recursiveSink() }'), (),
        UnresolvedRejection((FlowObligation.CALLBACK_CYCLE, FlowObligation.NO_INVOCATION_PROVEN)),
        within(named('recursiveWrapper'), 'recursiveWrapper(block)'),
    )
    escaped = RejectedLambdaCase(
        'external-escape', supply('externalEscapeEntry', 'escapeSink', 'externalEscapeWrapper', '{ escapeSink() }'),
        (forward('externalEscapeWrapper', 'externalEscape'),),
        UnresolvedRejection((FlowObligation.EXTERNAL_CALLABLE, FlowObligation.NO_INVOCATION_PROVEN)),
        within(named('externalEscape'), 'java.util.Collections.singletonList(block)'),
    )
    reference = ExcludedReferenceCase(
        'callable-reference', named('callableReferenceEntry'), named('referenceSink'),
        within(named('callableReferenceEntry'), 'sharedWrapper(::referenceSink)'),
        within(named('callableReferenceEntry'), '::referenceSink'),
        within(named('callableReferenceEntry'), 'referenceSink'),
    )
    formal_invocation = RejectedFormalCase('formal-invocation', named('invokeCallback'),
                                          formal('invokeCallback'), terminal, CallableValueRejection())
    # One grant retains sharedWrapper -> forwardOnce, then the next forwarding
    # admission is rejected. No terminal invocation has been retained. The two
    # authored alpha occurrences remain separately observable, with their exact
    # distinct supply proofs and closed, canonical-order resource obligations.
    resource = RejectedResourceCase(
        'alpha-result-capacity', alpha.supplies,
        UnresolvedRejection((FlowObligation.NO_INVOCATION_PROVEN, FlowObligation.RESULT_LIMIT_REACHED)),
        1, shared_route[1].call,
    )
    digest = sha256()
    for source in sources:
        digest.update(source.relative_path.encode())
        digest.update(b'\0')
        digest.update(source.text.encode())
        digest.update(b'\0')
    return SuiteOracle(root, digest.hexdigest(), symbols, (alpha, beta), (recursive, escaped, formal_invocation),
                       (reference,), (resource,))


def load_oracle(root: Path) -> SuiteOracle:
    canonical_root = root.resolve(strict=True)
    kotlin_sources = tuple(sorted(str(path.relative_to(canonical_root))
                                  for extension in ('*.kt', '*.kts')
                                  for path in canonical_root.glob('*/src/**/' + extension)
                                  if path.is_file()))
    if kotlin_sources != tuple(sorted(SOURCE_FILES)):
        raise FixtureIntegrityError('unexpected or missing Kotlin source paths')
    sources = tuple(SourceDocument(relative, (canonical_root / relative).read_text(encoding='utf-8'))
                    for relative in SOURCE_FILES)
    return materialize(str(canonical_root), sources)
