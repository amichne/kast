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
    UNAVAILABLE = 'UNAVAILABLE'
    CALLABLE_VALUE_UNPROVEN = 'CALLABLE_VALUE_UNPROVEN'


class FlowObligation(str, Enum):
    EXTERNAL_CALLABLE = 'EXTERNAL_CALLABLE'
    PARAMETER_ESCAPES = 'PARAMETER_ESCAPES'
    STORED_CALLBACK = 'STORED_CALLBACK'
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
class TransferOracle:
    source: SourceSpan
    target: SourceSpan
    source_role: str
    target_role: str
    kind: str


@dataclass(frozen=True)
class ForwardingOracle:
    source: FormalOracle
    target: FormalOracle
    argument: SourceSpan
    call: SourceSpan
    transfers: tuple[TransferOracle, ...] = ()


@dataclass(frozen=True)
class InvocationOracle:
    owner: SymbolOracle
    occurrence: SourceSpan


@dataclass(frozen=True)
class ForwardingGraphOracle:
    """Authored exhaustive formal inventory; cycles remain finite edge facts."""
    root: FormalOracle
    formals: tuple[FormalOracle, ...]
    forwardings: tuple[ForwardingOracle, ...]

    def __post_init__(self):
        if not self.formals or self.root not in self.formals or len(set(self.formals)) != len(self.formals):
            raise FixtureIntegrityError('graph must inventory its root and each exact formal once')
        if len(set(self.forwardings)) != len(self.forwardings):
            raise FixtureIntegrityError('graph forwarding edge duplicated')
        if any(edge.source not in self.formals or edge.target not in self.formals for edge in self.forwardings):
            raise FixtureIntegrityError('graph edge endpoint missing from exhaustive inventory')
        reached = {self.root}
        while True:
            successor = reached | {edge.target for edge in self.forwardings if edge.source in reached}
            if successor == reached:
                break
            reached = successor
        if reached != set(self.formals):
            raise FixtureIntegrityError('graph includes an unreachable formal')


@dataclass(frozen=True)
class UnresolvedRejection:
    obligations: tuple[FlowObligation, ...]
    scan: InvocationScan = InvocationScan.INCOMPLETE
    type: GraphRejectionType = GraphRejectionType.UNRESOLVED


@dataclass(frozen=True)
class CallableValueRejection:
    type: GraphRejectionType = GraphRejectionType.CALLABLE_VALUE_UNPROVEN


@dataclass(frozen=True)
class UnavailableRejection:
    cause: FlowObligation
    type: GraphRejectionType = GraphRejectionType.UNAVAILABLE


RejectionOracle = UnresolvedRejection | CallableValueRejection | UnavailableRejection


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
    graph: ForwardingGraphOracle

    def __post_init__(self):
        _admit_same_root_supplies(self.supplies)
        _admit_graph_supplies(self.supplies, self.graph)

    @property
    def supply(self) -> SupplyOracle:
        """The root's first supply; callers checking inventory must use ``supplies``."""
        return self.supplies[0]


@dataclass(frozen=True)
class CompleteEmptyCase:
    """An exhausted closed graph with no terminal invocation, never activation proof."""
    name: str
    supplies: tuple[SupplyOracle, ...]
    graph: ForwardingGraphOracle
    forbidden_targets: tuple[SymbolOracle, ...]

    def __post_init__(self):
        _admit_same_root_supplies(self.supplies)
        _admit_graph_supplies(self.supplies, self.graph)

    @property
    def supply(self) -> SupplyOracle:
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
class AbsentReceiver:
    type: str = field(default='ABSENT', init=False)


@dataclass(frozen=True)
class UnboundReceiver:
    type: str = field(default='UNBOUND', init=False)


@dataclass(frozen=True)
class BoundReceiver:
    occurrence: SourceSpan
    type: str = field(default='BOUND', init=False)


ReferenceReceiver = AbsentReceiver | UnboundReceiver | BoundReceiver


@dataclass(frozen=True)
class ExcludedReferenceCase:
    """Named calls exclude value creation; a supplied reference retains its callback proof."""
    name: str
    supplier: SymbolOracle
    target: SymbolOracle
    call: SourceSpan
    reference: SourceSpan
    target_occurrence: SourceSpan
    graph: ForwardingGraphOracle
    forwardings: tuple[ForwardingOracle, ...]
    invocation: InvocationOracle
    dispatch_receiver: ReferenceReceiver = AbsentReceiver()
    extension_receiver: ReferenceReceiver = AbsentReceiver()


@dataclass(frozen=True)
class RejectedFormalCase:
    name: str
    owner: SymbolOracle
    formal: FormalOracle
    invocation: InvocationOracle
    rejection: UnavailableRejection


@dataclass(frozen=True)
class RejectedResourceCase:
    """The same alpha routes at a result grant that cannot retain their witnesses."""
    name: str
    supplies: tuple[SupplyOracle, ...]
    rejection: UnresolvedRejection
    max_results: int
    cause_site: SourceSpan
    lexical_rejection: UnresolvedRejection
    original_coverage: CompleteCoverageOracle = CompleteCoverageOracle()

    def __post_init__(self):
        _admit_same_root_supplies(self.supplies)

    @property
    def supply(self) -> SupplyOracle:
        return self.supplies[0]


CompleteCaseOracle = CompleteCase | CompleteEmptyCase
CaseOracle = CompleteCaseOracle | RejectedLambdaCase | RejectedFormalCase | ExcludedReferenceCase


@dataclass(frozen=True)
class SuiteOracle:
    root: str
    source_sha256: str
    symbols: tuple[SymbolOracle, ...]
    complete_cases: tuple[CompleteCaseOracle, ...]
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


def _admit_graph_supplies(supplies: tuple[SupplyOracle, ...], graph: ForwardingGraphOracle) -> None:
    if any(supply.formal != graph.root for supply in supplies):
        raise FixtureIntegrityError('every supplier must bind the exact graph root')


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
    declarations = (
        (suppliers, 'alphaEntry',
         '// The suppliers share the formal route, but each owns a distinct callback body.\n'
         'fun alphaEntry(): String {\n'
         '    sharedWrapper { alphaSink() }\n'
         '    return sharedWrapper { alphaSink(); "alpha" }\n'
         '}'),
        (suppliers, 'betaEntry', 'fun betaEntry(): String = sharedWrapper { betaSink() }'),
        (suppliers, 'callableReferenceEntry',
         '// Callable references retain supplied-value proof without becoming named calls.\n'
         'fun callableReferenceEntry(): String = sharedWrapper(::referenceSink)'),
        (suppliers, 'recursiveEntry',
         '// Static recursive graphs: never execute these functions to qualify a query.\n'
         'fun recursiveEntry(): String = recursiveWrapper { recursiveSink() }'),
        (suppliers, 'selfRecursiveEntry',
         'fun selfRecursiveEntry(): String {\n'
         '    selfInvokeWrapper { selfRecursiveSink() }\n'
         '    return selfInvokeWrapper { selfRecursiveSink(); "self" }\n'
         '}'),
        (suppliers, 'mutualRecursiveEntry',
         'fun mutualRecursiveEntry(): String = mutualFirst { mutualRecursiveSink() }'),
        (suppliers, 'externalEscapeEntry', 'fun externalEscapeEntry(): Int = externalEscapeWrapper { escapeSink() }'),
        (forwarding, 'sharedWrapper', 'fun sharedWrapper(block: () -> String): String = forwardOnce(block)'),
        (forwarding, 'forwardOnce', 'fun forwardOnce(block: () -> String): String = invokeCallback(block)'),
        (forwarding, 'recursiveWrapper', 'fun recursiveWrapper(block: () -> String): String = recursiveWrapper(block)'),
        (forwarding, 'selfInvokeWrapper',
         'fun selfInvokeWrapper(block: () -> String): String {\n'
         '    block()\n'
         '    return selfInvokeWrapper(block)\n'
         '}'),
        (forwarding, 'mutualFirst', 'fun mutualFirst(block: () -> String): String = mutualSecond(block)'),
        (forwarding, 'mutualSecond',
         'fun mutualSecond(block: () -> String): String {\n'
         '    invokeCallback(block)\n'
         '    return mutualFirst(block)\n'
         '}'),
        (forwarding, 'externalEscapeWrapper', 'fun externalEscapeWrapper(block: () -> String): Int = externalEscape(block)'),
        (invocation, 'invokeCallback', 'fun invokeCallback(block: () -> String): String = block()'),
        (invocation, 'externalEscape', 'fun externalEscape(block: () -> String): Int = java.util.Collections.singletonList(block).size'),
        (invocation, 'alphaSink', 'fun alphaSink(): String = "alpha"'),
        (invocation, 'betaSink', 'fun betaSink(): String = "beta"'),
        (invocation, 'referenceSink', 'fun referenceSink(): String = "reference"'),
        (invocation, 'recursiveSink', 'fun recursiveSink(): String = "recursive"'),
        (invocation, 'selfRecursiveSink', 'fun selfRecursiveSink(): String = "self-recursive"'),
        (invocation, 'mutualRecursiveSink', 'fun mutualRecursiveSink(): String = "mutual-recursive"'),
        (invocation, 'escapeSink', 'fun escapeSink(): String = "escape"'),
        (invocation, 'aliasSink', 'fun aliasSink(): String = "alias"'),
        (invocation, 'mutableAliasSink', 'fun mutableAliasSink(): String = "mutable"'),
        (suppliers, 'aliasEntry', 'fun aliasEntry(): String = fixture.staticcallbacks.forwarding.aliasWrapper { fixture.staticcallbacks.invocation.aliasSink(); "alias" }'),
        (suppliers, 'mutableAliasEntry', 'fun mutableAliasEntry(): String = fixture.staticcallbacks.forwarding.mutableAliasWrapper { fixture.staticcallbacks.invocation.mutableAliasSink(); "mutable" }'),
        (forwarding, 'aliasWrapper', 'fun aliasWrapper(block: () -> String): String {\n'
         '    val first = block\n    val second = first\n    return invokeCallback(second)\n}'),
        (forwarding, 'mutableAliasWrapper', 'fun mutableAliasWrapper(block: () -> String): String {\n'
         '    var alias = block\n    return invokeCallback(alias)\n}'),
        (invocation, 'boundSink', 'fun boundSink(): String = "bound"', 'ReferenceReceiver'),
        (invocation, 'unboundSink', 'fun unboundSink(): String = "unbound"', 'ReferenceReceiver'),
        (invocation, 'invokeReceiverCallback', 'fun invokeReceiverCallback(receiver: ReferenceReceiver, block: (ReferenceReceiver) -> String): String = block(receiver)'),
        (suppliers, 'boundReferenceEntry', 'fun boundReferenceEntry(receiver: fixture.staticcallbacks.invocation.ReferenceReceiver): String =\n'
         '    fixture.staticcallbacks.forwarding.sharedWrapper(receiver::boundSink)'),
        (suppliers, 'unboundReferenceEntry', 'fun unboundReferenceEntry(receiver: fixture.staticcallbacks.invocation.ReferenceReceiver): String =\n'
         '    fixture.staticcallbacks.invocation.invokeReceiverCallback(receiver, fixture.staticcallbacks.invocation.ReferenceReceiver::unboundSink)'),
    )

    def symbol(source: SourceDocument, name: str, declaration: str, owner: str = '') -> SymbolOracle:
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
        return SymbolOracle(name, package + '.' + (owner + '.' if owner else '') + name, _span(root, source, declaration), name_site)

    symbols = tuple(symbol(*declaration) for declaration in declarations)

    def named(name: str) -> SymbolOracle:
        return next(value for value in symbols if value.name == name)

    def source_for(value: SymbolOracle) -> SourceDocument:
        return next(source for source in sources if str(Path(root) / source.relative_path) == value.declaration.file)

    def within(value: SymbolOracle, text: str) -> SourceSpan:
        return _span(root, source_for(value), text, value.declaration.text)

    def formal(name: str) -> FormalOracle:
        value = named(name)
        if name == 'invokeReceiverCallback':
            return FormalOracle(value, within(value, 'block: (ReferenceReceiver) -> String'), 1)
        return FormalOracle(value, within(value, 'block: () -> String'), 0)

    def supply(entry: str, sink: str, receiver: str, body: str, qualifier: str = "") -> SupplyOracle:
        supplier, target = named(entry), named(sink)
        targets = _occurrences(root, source_for(supplier), sink, body, 1)
        return SupplyOracle(supplier, target, within(supplier, body), targets,
                            within(supplier, qualifier + receiver + ' ' + body), formal(receiver))

    def forward(source: str, target: str) -> ForwardingOracle:
        origin, destination = formal(source), formal(target)
        call = target + '(block)'
        site = within(origin.callable, call)
        argument_start = site.range.startInclusive + len(target) + 1
        argument = SourceSpan(site.file, Utf16Range(argument_start, argument_start + len('block')), 'block')
        return ForwardingOracle(origin, destination, argument, site)

    shared_route = (forward('sharedWrapper', 'forwardOnce'), forward('forwardOnce', 'invokeCallback'))
    shared_graph = ForwardingGraphOracle(formal('sharedWrapper'),
        (formal('sharedWrapper'), formal('forwardOnce'), formal('invokeCallback')), shared_route)
    terminal = InvocationOracle(named('invokeCallback'), within(named('invokeCallback'), 'block()'))
    alpha = CompleteCase('alpha', (
        supply('alphaEntry', 'alphaSink', 'sharedWrapper', '{ alphaSink() }'),
        supply('alphaEntry', 'alphaSink', 'sharedWrapper', '{ alphaSink(); "alpha" }'),
    ), shared_route, terminal,
                         (named('betaSink'), named('referenceSink')), shared_graph)
    beta = CompleteCase('beta', (supply('betaEntry', 'betaSink', 'sharedWrapper', '{ betaSink() }'),),
                        shared_route, terminal,
                        (named('alphaSink'), named('referenceSink')), shared_graph)
    recursive = CompleteEmptyCase(
        'recursive', (supply('recursiveEntry', 'recursiveSink', 'recursiveWrapper', '{ recursiveSink() }'),),
        ForwardingGraphOracle(formal('recursiveWrapper'), (formal('recursiveWrapper'),),
                              (forward('recursiveWrapper', 'recursiveWrapper'),)),
        (named('alphaSink'), named('betaSink')),
    )
    self_recursive = CompleteCase(
        'self-recursive-invocation', (
            supply('selfRecursiveEntry', 'selfRecursiveSink', 'selfInvokeWrapper', '{ selfRecursiveSink() }'),
            supply('selfRecursiveEntry', 'selfRecursiveSink', 'selfInvokeWrapper', '{ selfRecursiveSink(); "self" }'),
        ), (), InvocationOracle(named('selfInvokeWrapper'), within(named('selfInvokeWrapper'), 'block()')),
        (named('alphaSink'), named('betaSink'), named('mutualRecursiveSink')),
        ForwardingGraphOracle(formal('selfInvokeWrapper'), (formal('selfInvokeWrapper'),),
                              (forward('selfInvokeWrapper', 'selfInvokeWrapper'),)),
    )
    mutual_route = (forward('mutualFirst', 'mutualSecond'), forward('mutualSecond', 'invokeCallback'))
    mutual_recursive = CompleteCase(
        'mutual-recursive-invocation',
        (supply('mutualRecursiveEntry', 'mutualRecursiveSink', 'mutualFirst', '{ mutualRecursiveSink() }'),),
        mutual_route, terminal, (named('alphaSink'), named('betaSink'), named('selfRecursiveSink')),
        ForwardingGraphOracle(formal('mutualFirst'),
            (formal('mutualFirst'), formal('mutualSecond'), formal('invokeCallback')),
            (mutual_route[0], forward('mutualSecond', 'mutualFirst'), mutual_route[1])),
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
        shared_graph, shared_route, terminal,
    )
    bound_reference = ExcludedReferenceCase(
        'bound-callable-reference', named('boundReferenceEntry'), named('boundSink'),
        within(named('boundReferenceEntry'), 'fixture.staticcallbacks.forwarding.sharedWrapper(receiver::boundSink)'),
        within(named('boundReferenceEntry'), 'receiver::boundSink'),
        within(named('boundReferenceEntry'), 'boundSink'), shared_graph, shared_route, terminal,
        BoundReceiver(_span(root, suppliers, 'receiver', 'receiver::boundSink')))
    unbound_formal = formal('invokeReceiverCallback')
    unbound_reference = ExcludedReferenceCase(
        'unbound-callable-reference', named('unboundReferenceEntry'), named('unboundSink'),
        within(named('unboundReferenceEntry'), 'fixture.staticcallbacks.invocation.invokeReceiverCallback(receiver, fixture.staticcallbacks.invocation.ReferenceReceiver::unboundSink)'),
        within(named('unboundReferenceEntry'), 'fixture.staticcallbacks.invocation.ReferenceReceiver::unboundSink'),
        within(named('unboundReferenceEntry'), 'unboundSink'),
        ForwardingGraphOracle(unbound_formal, (unbound_formal,), ()), (),
        InvocationOracle(named('invokeReceiverCallback'), within(named('invokeReceiverCallback'), 'block(receiver)')),
        UnboundReceiver())
    formal_invocation = RejectedFormalCase('formal-invocation', named('invokeCallback'),
                                          formal('invokeCallback'), terminal, UnavailableRejection(FlowObligation.STORED_CALLBACK))
    # One grant retains sharedWrapper -> forwardOnce, then the next forwarding
    # admission is rejected. No terminal invocation has been retained. The two
    # authored alpha occurrences remain separately observable, with their exact
    # distinct supply proofs and closed, canonical-order resource obligations.
    resource = RejectedResourceCase(
        'alpha-result-capacity', alpha.supplies,
        UnresolvedRejection((FlowObligation.RESULT_LIMIT_REACHED,)),
        1, alpha.supply.call,
        UnresolvedRejection((FlowObligation.NO_INVOCATION_PROVEN, FlowObligation.RESULT_LIMIT_REACHED)),
    )
    alias_owner = named('aliasWrapper')
    def alias_site(expression: str, region: str) -> SourceSpan:
        return _span(root, forwarding, expression, region)
    first_binding = within(alias_owner, 'val first = block')
    second_binding = within(alias_owner, 'val second = first')
    parameter_read = alias_site('block', 'val first = block')
    first_read = alias_site('first', 'val second = first')
    second_read = alias_site('second', 'invokeCallback(second)')
    alias_route = (ForwardingOracle(formal('aliasWrapper'), formal('invokeCallback'), second_read,
        within(alias_owner, 'invokeCallback(second)'), (
            TransferOracle(parameter_read, first_binding, 'EXPRESSION_RESULT', 'LOCAL_BINDING', 'LOCAL_BINDING'),
            TransferOracle(first_binding, first_read, 'LOCAL_BINDING', 'LOCAL_READ', 'LOCAL_READ'),
            TransferOracle(first_read, second_binding, 'LOCAL_READ', 'LOCAL_BINDING', 'LOCAL_BINDING'),
            TransferOracle(second_binding, second_read, 'LOCAL_BINDING', 'LOCAL_READ', 'LOCAL_READ'),
        )),)
    alias = CompleteCase('immutable-alias-forwarding',
        (supply('aliasEntry', 'aliasSink', 'aliasWrapper', '{ fixture.staticcallbacks.invocation.aliasSink(); "alias" }', 'fixture.staticcallbacks.forwarding.'),), alias_route,
        terminal, (named('betaSink'),),
        ForwardingGraphOracle(formal('aliasWrapper'), (formal('aliasWrapper'), formal('invokeCallback')), alias_route))
    mutable = RejectedLambdaCase('mutable-alias-forwarding',
        supply('mutableAliasEntry', 'mutableAliasSink', 'mutableAliasWrapper', '{ fixture.staticcallbacks.invocation.mutableAliasSink(); "mutable" }', 'fixture.staticcallbacks.forwarding.'), (),
        UnresolvedRejection((FlowObligation.PARAMETER_ESCAPES, FlowObligation.NO_INVOCATION_PROVEN)),
        within(named('mutableAliasWrapper'), 'var alias = block'))
    digest = sha256()
    for source in sources:
        digest.update(source.relative_path.encode())
        digest.update(b'\0')
        digest.update(source.text.encode())
        digest.update(b'\0')
    return SuiteOracle(root, digest.hexdigest(), symbols,
                       (alpha, beta, recursive, self_recursive, mutual_recursive, alias), (escaped, formal_invocation, mutable),
                       (reference, bound_reference, unbound_reference), (resource,))


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
