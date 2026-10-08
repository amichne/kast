"""Independent acceptance inputs for immutable callback and fact-reuse qualification.

Expected target names and edit outcomes are authored here, never obtained from a
Kast response or from a second resolver. These inputs do not establish native
support. The source inventory and anchors fail closed before a host is contacted.
"""
from dataclasses import dataclass, field
from enum import Enum
from hashlib import sha256
from pathlib import Path

from static_callback_oracle import FixtureIntegrityError, SourceDocument


class Claim(str, Enum):
    COMPLETE = 'COMPLETE'
    COMPLETE_EMPTY = 'COMPLETE_EMPTY'
    TYPED_REJECTION = 'TYPED_REJECTION'


class Boundary(str, Enum):
    TRY_FINALLY = 'TRY_FINALLY'
    TRY_ABRUPT = 'TRY_ABRUPT'
    MUTABLE_STORAGE = 'MUTABLE_STORAGE'
    EXTERNAL_TRANSFER = 'EXTERNAL_TRANSFER'
    FACTORY_RECEIVER = 'FACTORY_RECEIVER'
    CAPTURE_FORWARDING = 'CAPTURE_FORWARDING'
    BODY_GETTER = 'BODY_GETTER'
    BODY_OPERATOR = 'BODY_OPERATOR'
    BODY_SUBJECTFUL_WHEN = 'BODY_SUBJECTFUL_WHEN'


@dataclass(frozen=True)
class Complete:
    targets: tuple[str, ...]
    type: Claim = field(default=Claim.COMPLETE, init=False)

    def __post_init__(self):
        if not self.targets or len(set(self.targets)) != len(self.targets):
            raise FixtureIntegrityError('complete target inventory must be nonempty and unique')


@dataclass(frozen=True)
class CompleteEmpty:
    type: Claim = field(default=Claim.COMPLETE_EMPTY, init=False)


@dataclass(frozen=True)
class Rejected:
    boundary: Boundary
    type: Claim = field(default=Claim.TYPED_REJECTION, init=False)


@dataclass(frozen=True)
class Case:
    name: str
    source: str
    seed: str
    anchor: str
    expected: Complete | CompleteEmpty | Rejected


@dataclass(frozen=True)
class SourceEdit:
    file: str
    before: str
    after: str

    def __post_init__(self):
        path = Path(self.file)
        if path.is_absolute() or '..' in path.parts or not self.before or self.before == self.after:
            raise FixtureIntegrityError('edit must change one relative owned source preimage')


@dataclass(frozen=True)
class Mutation:
    name: str
    edit: SourceEdit
    affected_seeds: tuple[str, ...]
    independently_reusable_seed: str
    expected: Complete | CompleteEmpty


SUPPLIERS = 'suppliers/src/main/kotlin/fixture/immutable/suppliers/Suppliers.kt'
FORWARDING = 'forwarding/src/main/kotlin/fixture/immutable/forwarding/Forwarding.kt'
INVOCATION = 'invocation/src/main/kotlin/fixture/immutable/invocation/Invocation.kt'
INDEPENDENT = 'independent/src/main/kotlin/fixture/immutable/independent/Independent.kt'
SOURCE_FILES = (SUPPLIERS, FORWARDING, INVOCATION, INDEPENDENT)


def case(name, seed, anchor, expected, source=SUPPLIERS):
    return Case(name, source, seed, anchor, expected)


CASES = (
    case('reference', 'referenceEntry', 'wrapper(::alphaTarget)', Complete(('alphaTarget',))),
    case('bound-receiver', 'boundReferenceEntry', 'wrapper(receiver::member)', Complete(('Receiver.member',))),
    case('unbound-receiver', 'unboundReferenceEntry', 'invokeReceiver(receiver, Receiver::member)', Complete(('Receiver.member',))),
    case('immutable-alias', 'aliasEntry', 'val callback = { alphaTarget() }\n    val alias = callback\n    return wrapper(alias)', Complete(('alphaTarget',))),
    case('local-reference', 'localReferenceEntry', 'val first = ::alphaTarget\n    val second = first\n    return wrapper(second)', Complete(('alphaTarget',))),
    case('direct-branch', 'directBranchEntry', 'wrapper(if (first) ::alphaTarget else ::betaTarget)', Complete(('alphaTarget', 'betaTarget'))),
    case('local-branch', 'localBranchEntry', 'val selected = if (first) ::alphaTarget else ::betaTarget\n    return wrapper(selected)', Complete(('alphaTarget', 'betaTarget'))),
    case('direct-try', 'directTryEntry', 'wrapper(try { ::alphaTarget } catch(e: Exception) { ::betaTarget })', Complete(('alphaTarget', 'betaTarget'))),
    case('local-try', 'localTryEntry', 'val callback = try { ::alphaTarget } catch(e: Exception) { ::betaTarget }\n    val alias = callback\n    return wrapper(alias)', Complete(('alphaTarget', 'betaTarget'))),
    case('nested-try', 'nestedTryEntry', 'try { try { ::alphaTarget } catch(e: IllegalStateException) { ::betaTarget } }\n    catch(e: Exception) { ::betaTarget }', Complete(('alphaTarget', 'betaTarget'))),
    case('factory-try', 'factoryTryEntry', 'wrapper(tryChoiceFactory())', Complete(('alphaTarget', 'betaTarget'))),
    case('try-finally-control', 'finallyTryEntry', 'wrapper(try { ::alphaTarget } finally { betaTarget() })', Rejected(Boundary.TRY_FINALLY)),
    case('factory-finally-control', 'finallyFactoryEntry', 'wrapper(finallyOverrideFactory())', Rejected(Boundary.TRY_FINALLY)),
    case('try-abrupt-control', 'abruptTryEntry', 'wrapper(abruptTryFactory())', Rejected(Boundary.TRY_ABRUPT)),
    case('anonymous-function' , 'anonymousEntry', 'wrapper(fun(): String { return alphaTarget() })', Complete(('alphaTarget',))),
    case('default-omission', 'defaultEntry', 'fun defaultEntry(): String = defaultWrapper()', Complete(('alphaTarget',))),
    case('default-override', 'explicitDefaultEntry', 'defaultWrapper(::betaTarget)', Complete(('betaTarget',))),
    case('generic', 'genericEntry', 'invokeGeneric(receiver, Receiver::member)', Complete(('Receiver.member',))),
    case('capture-alpha', 'capturedAlphaEntry', 'wrapper(captureFactory(::alphaTarget))', Complete(('alphaTarget',))),
    case('capture-beta', 'capturedBetaEntry', 'wrapper(captureFactory(::betaTarget))', Complete(('betaTarget',))),
    case('identity-return', 'identityReturnedEntry', 'wrapper(identityFactory(::alphaTarget))', Complete(('alphaTarget',))),
    case('captured-alias', 'capturedAliasEntry', 'wrapper(capturedAliasFactory(::betaTarget))', Complete(('betaTarget',))),
    case('direct-returned-invocation', 'directReturnedEntry', 'fun directReturnedEntry(): String = captureFactory(::alphaTarget)()', Complete(('alphaTarget',))),
    case('local-returned-invocation', 'localReturnedEntry', 'val action = captureFactory(::betaTarget)\n    return action()', Complete(('betaTarget',))),
    case('mutable-capture-control', 'mutableCaptureEntry', 'wrapper(mutableCaptureFactory(::alphaTarget))', Rejected(Boundary.MUTABLE_STORAGE)),
    case('forwarding-capture-control', 'forwardingCaptureEntry', 'wrapper(forwardingCaptureFactory(::alphaTarget))', Rejected(Boundary.CAPTURE_FORWARDING)),
    case('receiver-factory-control', 'receiverFactoryEntry', 'wrapper(factory.create(::alphaTarget))', Rejected(Boundary.FACTORY_RECEIVER)),
    case('getter-control', 'getterEntry', 'wrapper(getterFactory())', Rejected(Boundary.BODY_GETTER)),
    case('operator-control', 'operatorEntry', 'wrapper(operatorFactory(::alphaTarget, 1))', Rejected(Boundary.BODY_OPERATOR)),
    case('subjectful-when-control', 'subjectfulWhenEntry', 'wrapper(subjectfulWhenFactory(1))', Rejected(Boundary.BODY_SUBJECTFUL_WHEN)),
    case('branch-alternatives', 'branchEntry', 'wrapper(choiceFactory(first))', Complete(('alphaTarget', 'betaTarget'))),
    case('stored-not-invoked', 'storedNeverInvokedEntry', 'neverInvoked(::alphaTarget)', CompleteEmpty()),
    case('mutable-control', 'mutableEntry', 'var callback = ::alphaTarget\n    callback = ::betaTarget\n    return wrapper(callback)', Rejected(Boundary.MUTABLE_STORAGE)),
    case('external-control', 'externalEntry', 'externalEscape(::alphaTarget)', Rejected(Boundary.EXTERNAL_TRANSFER)),
    case('formal-empty-inventory', 'inventoryWrapper', 'fun inventoryWrapper(block: () -> String): String = block()', CompleteEmpty(), FORWARDING),
    case('formal-supplier-inventory', 'knownSuppliers', 'fun knownSuppliers(block: () -> String): String = block()', Complete(('alphaTarget', 'betaTarget')), FORWARDING),
    case('independent-module', 'independentEntry', 'independentWrapper { independentTarget() }', Complete(('independentTarget',)), INDEPENDENT),
)

# Each mutation is applied to the original fixture, not cumulatively. Expected
# answers concern callback invocation targets, except overload/dispatch entries
# whose expected names identify direct compiler-resolved calls.
MUTATIONS = (
    Mutation('body-only', SourceEdit(SUPPLIERS, 'val callback = { alphaTarget() }', 'val callback = { betaTarget() }'),
             ('aliasEntry',), 'independentEntry', Complete(('betaTarget',))),
    Mutation('factory-return', SourceEdit(FORWARDING, 'fun captureFactory(block: () -> String): () -> String = { block() }',
                                         'fun captureFactory(block: () -> String): () -> String = { betaTarget() }'),
             ('capturedAlphaEntry', 'capturedBetaEntry'), 'independentEntry', Complete(('betaTarget',))),
    Mutation('add-overload', SourceEdit(SUPPLIERS, '    return action()\n}',
                                       '    return action()\n}\nfun overload(value: String): String = betaTarget()'),
             ('overloadEntry',), 'independentEntry', Complete(('overload(String)',))),
    Mutation('add-override', SourceEdit(INVOCATION, 'class Derived : Base()',
                                      'class Derived : Base() { override fun target(): String = "derived" }'),
             ('dispatchEntry',), 'independentEntry', Complete(('Base.target',))),
    Mutation('empty-to-invoked', SourceEdit(FORWARDING, 'fun neverInvoked(block: () -> String): String = "unused"',
                                          'fun neverInvoked(block: () -> String): String = block()'),
             ('storedNeverInvokedEntry',), 'independentEntry', Complete(('alphaTarget',))),
    Mutation('add-supplier', SourceEdit(SUPPLIERS, 'fun referenceEntry(): String = wrapper(::alphaTarget)',
                                      'fun referenceEntry(): String = wrapper(::alphaTarget)\nfun addedSupplier(): String = inventoryWrapper(::betaTarget)'),
             ('inventoryWrapper',), 'independentEntry', Complete(('betaTarget',))),
)


def source_digest(sources):
    digest = sha256()
    for source in sources:
        digest.update(source.relative_path.encode() + b'\0' + source.text.encode() + b'\0')
    return digest.hexdigest()


def validate_sources(sources):
    if tuple(source.relative_path for source in sources) != SOURCE_FILES:
        raise FixtureIntegrityError('exact ordered four-module source inventory required')
    by_path = {source.relative_path: source.text for source in sources}
    for expected in CASES:
        if by_path[expected.source].count(expected.anchor) != 1:
            raise FixtureIntegrityError('case anchor missing or ambiguous: ' + expected.name)
    for mutation in MUTATIONS:
        if by_path[mutation.edit.file].count(mutation.edit.before) != 1:
            raise FixtureIntegrityError('edit preimage missing or ambiguous: ' + mutation.name)
    return source_digest(sources)


def apply_edit(sources, edit):
    """Pure transformation. The native mutation owner separately checks ownership."""
    if sum(source.relative_path == edit.file for source in sources) != 1:
        raise FixtureIntegrityError('edit target must be inventoried exactly once')
    selected = next(source for source in sources if source.relative_path == edit.file)
    if selected.text.count(edit.before) != 1:
        raise FixtureIntegrityError('edit preimage missing or ambiguous')
    return tuple(SourceDocument(source.relative_path, source.text.replace(edit.before, edit.after, 1))
                 if source == selected else source for source in sources)


def load_sources(root):
    root = Path(root).resolve(strict=True)
    actual = sorted(str(path.relative_to(root)) for extension in ('*.kt', '*.kts')
                    for path in root.glob('*/src/**/' + extension))
    if actual != sorted(SOURCE_FILES):
        raise FixtureIntegrityError('unlisted or missing Kotlin fixture source')
    sources = tuple(SourceDocument(file, (root / file).read_text(encoding='utf-8')) for file in SOURCE_FILES)
    validate_sources(sources)
    return sources
