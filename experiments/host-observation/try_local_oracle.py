"""Independent source intent for try/catch results and local declaration bindings.

This module reads no Kast output and mints no compiler identity. Source anchors
are only test inputs; native K2 must independently prove every target and edge.
Existing representation fixture anchors are unchanged by the appended cases.
"""

from dataclasses import asdict, dataclass
from enum import Enum
from hashlib import sha256
from pathlib import Path
import argparse
import json


SOURCE = 'logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt'
TEMPLATE = Path(__file__).parent / 'semantic-fixture/value-flow/RepresentationImpactFixture.kt'
OTHER_SOURCE = 'logging/src/main/kotlin/representation/other/LocalIdentityOtherFixture.kt'
OTHER_TEMPLATE = TEMPLATE.with_name('LocalIdentityOtherFixture.kt')


class FixtureIntegrityError(ValueError):
    """Missing or ambiguous authored source is fixture failure, never product success."""


class CompletionExpectation(str, Enum):
    NORMAL_RETURN = 'NORMAL_RETURN'
    DISCARDED_RESULT = 'DISCARDED_RESULT'
    FINALLY_UNSUPPORTED = 'FINALLY_UNSUPPORTED'
    UNIT_UNSUPPORTED = 'UNIT_UNSUPPORTED'
    ABRUPT_COMPLETION = 'ABRUPT_COMPLETION'


@dataclass(frozen=True)
class SourceSpan:
    file: str
    startInclusive: int
    endExclusive: int
    text: str


@dataclass(frozen=True)
class BranchResult:
    tryRange: SourceSpan
    branchRange: SourceSpan
    alternative: str


@dataclass(frozen=True)
class FlowCase:
    name: str
    owner: SourceSpan
    producer: SourceSpan
    expectation: CompletionExpectation
    normalBranchResults: int
    branch: str
    forbiddenProducer: tuple[SourceSpan, ...]
    branchResults: tuple[BranchResult, ...]
    returnRanges: tuple[SourceSpan, ...] = ()


@dataclass(frozen=True)
class LocalCase:
    name: str
    owner: SourceSpan
    declaration: SourceSpan
    nameSite: SourceSpan
    kind: str
    mutable: bool
    references: tuple[SourceSpan, ...]
    forbiddenReferences: tuple[SourceSpan, ...]
    ownerRange: SourceSpan | None = None
    lexicalOwners: tuple[SourceSpan, ...] = ()


@dataclass(frozen=True)
class SourceInventory:
    file: str
    sha256: str


@dataclass(frozen=True)
class CallableIntent:
    producerPrefix: str
    declarationName: SourceSpan


@dataclass(frozen=True)
class SourceIntent:
    sourcePath: str
    sourceSha256: str
    flows: tuple[FlowCase, ...]
    locals: tuple[LocalCase, ...]
    additionalSources: tuple[SourceInventory, ...] = ()
    type: str = 'SOURCE_TEXT_INTENT_REQUIRES_K2'
    offsetUnit: str = 'UTF16'
    schemaVersion: int = 1
    scenario: str = 'fixture'
    sourceDirectory: str = 'logging'
    callables: tuple[CallableIntent, ...] = ()


def span(text, fragment, occurrence=0, start=0, end=None):
    end = len(text) if end is None else end
    positions = []
    position = start
    while True:
        position = text.find(fragment, position, end)
        if position < 0:
            break
        positions.append(position)
        position += len(fragment)
    if occurrence < 0 or occurrence >= len(positions):
        raise FixtureIntegrityError('authored fragment occurrence unavailable')
    offset = positions[occurrence]
    return SourceSpan(SOURCE, len(text[:offset].encode('utf-16-le')) // 2,
                      len(text[:offset + len(fragment)].encode('utf-16-le')) // 2, fragment)


def owner(text, name):
    fragment = 'fun ' + name + '('
    if text.count(fragment) != 1:
        raise FixtureIntegrityError('authored owner missing or duplicated: ' + name)
    start = text.index(fragment)
    end = text.find('\nfun ', start + len(fragment))
    end = len(text) if end < 0 else end
    return start, end, span(text, name, start=start, end=start + len(fragment))


FLOW_SPECS = (
    ('try-result', 'tryResult', 'Voltage.encrypt(input)', CompletionExpectation.NORMAL_RETURN, 1, 'TRY'),
    ('try-fallback', 'tryFallback', 'Voltage.encrypt(input)', CompletionExpectation.NORMAL_RETURN, 1, 'TRY'),
    ('catch-fallback', 'tryFallback', 'Hiped.encrypt("fallback")', CompletionExpectation.NORMAL_RETURN, 1, 'CATCH'),
    ('catch-result', 'catchResult', 'Voltage.encrypt(input)', CompletionExpectation.NORMAL_RETURN, 1, 'CATCH'),
    ('non-final', 'tryNonFinal', 'Voltage.encrypt(input)', CompletionExpectation.DISCARDED_RESULT, 0, 'NONE'),
    ('nested', 'tryNested', 'Voltage.encrypt(input)', CompletionExpectation.NORMAL_RETURN, 2, 'TRY'),
    ('explicit-return', 'tryExplicitReturn', 'Voltage.encrypt(input)', CompletionExpectation.NORMAL_RETURN, 0, 'NONE'),
    ('unit', 'tryUnit', 'Voltage.encrypt(input)', CompletionExpectation.DISCARDED_RESULT, 0, 'NONE'),
    ('finally-result', 'tryFinallyResult', 'Voltage.encrypt(input)', CompletionExpectation.FINALLY_UNSUPPORTED, 0, 'NONE'),
    ('finally-return', 'tryFinallyReturn', 'Voltage.encrypt(input)', CompletionExpectation.FINALLY_UNSUPPORTED, 0, 'NONE'),
    ('finally-throws', 'tryFinallyThrows', 'Voltage.encrypt(input)', CompletionExpectation.FINALLY_UNSUPPORTED, 0, 'NONE'),
)


def branch_results(text, function, start, end, branch, count):
    """Locate authored literal branch snippets; do not infer expected flow from syntax."""
    if count == 0:
        return ()
    begin = text.index('try {', start, end)
    whole_try = text[begin:end].rstrip()
    if function != 'tryNested':
        expression = 'Hiped.encrypt("fallback")' if function == 'tryFallback' and branch == 'CATCH' else 'Voltage.encrypt(input)'
        body = '{\n        ' + expression + '\n    }'
        return (BranchResult(span(text, whole_try, start=start, end=end),
                             span(text, body, start=start, end=end), branch),)
    inner = ('try {\n            Voltage.encrypt(input)\n        } catch (inner: IllegalArgumentException) {\n'
             '            throw IllegalStateException("inner", inner)\n        }')
    outer_body = '{\n        ' + inner + '\n    }'
    return (BranchResult(span(text, inner, start=start, end=end),
                         span(text, '{\n            Voltage.encrypt(input)\n        }', start=start, end=end), 'TRY'),
            BranchResult(span(text, whole_try, start=start, end=end),
                         span(text, outer_body, start=start, end=end), 'TRY'))


def materialize(text):
    """Derive only positions of independently authored declarations and expressions."""
    flows = []
    for name, function, expression, expectation, branches, branch in FLOW_SPECS:
        start, end, owning = owner(text, function)
        if text[start:end].count(expression) != 1:
            raise FixtureIntegrityError('authored producer missing or duplicated: ' + name)
        forbidden = ()
        if function == 'tryFallback':
            other = 'Hiped.encrypt("fallback")' if branch == 'TRY' else 'Voltage.encrypt(input)'
            forbidden = (span(text, other, start=start, end=end),)
        producer = span(text, expression, start=start, end=end)
        results = branch_results(text, function, start, end, branch, branches)
        returns = ((results[-1].tryRange if results else producer),) if expectation == CompletionExpectation.NORMAL_RETURN else ()
        flows.append(FlowCase(name, owning, producer,
                              expectation, branches, branch, forbidden,
                              results, returns))
    locals_ = []

    def binding(name, function, declaration, local_name, kind, mutable, reads, excluded):
        start, end, owning = owner(text, function)
        declaration_span = span(text, declaration, start=start, end=end)
        declaration_span = SourceSpan(SOURCE, declaration_span.startInclusive,
                                      declaration_span.endExclusive - len(declaration) + len(declaration.rstrip()),
                                      declaration.rstrip())
        declaration_offset = text.index(declaration, start, end)
        name_span = span(text, local_name, start=declaration_offset,
                         end=declaration_offset + len(declaration))
        def reference(fragment, occurrence):
            whole = span(text, fragment, occurrence, start, end)
            prefix = fragment.index(local_name)
            return SourceSpan(SOURCE, whole.startInclusive + prefix,
                              whole.startInclusive + prefix + len(local_name), local_name)
        whole_owner = text[start:end].rstrip()
        owner_range = span(text, whole_owner, start=start, end=end)
        outer_body = span(text, whole_owner[whole_owner.index('{'):], start=start, end=end)
        lexical = (outer_body,)
        if name == 'shadowed-val':
            lexical += (span(text, '{\n        val binding = Hiped.encrypt(input)\n        display(binding)\n    }', start=start, end=end),)
        if name == 'shadowed-function':
            lexical += (span(text, '{\n        fun named(value: String): String = value + "inner"\n        display(named(input))\n    }', start=start, end=end),)
        locals_.append(LocalCase(name, owning, declaration_span, name_span, kind, mutable,
                                  tuple(reference(*read) for read in reads),
                                  tuple(reference(*read) for read in excluded), owner_range, lexical))

    binding('outer-val', 'localBindings', 'val binding = Voltage.encrypt(input)', 'binding', 'PROPERTY', False,
            (('display(binding)', 0), ('display(binding)', 2)), (('display(binding)', 1),))
    binding('shadowed-val', 'localBindings', 'val binding = Hiped.encrypt(input)', 'binding', 'PROPERTY', False,
            (('display(binding)', 1),), (('display(binding)', 0), ('display(binding)', 2)))
    binding('mutable-var', 'localBindings', 'var mutable = Voltage.encrypt(input)', 'mutable', 'PROPERTY', True,
            (('display(mutable)', 0), ('mutable = Hiped.encrypt(input)', 0), ('display(mutable)', 1)), ())
    binding('other-owner-val', 'localOtherOwner', 'val binding = Voltage.encrypt(input)', 'binding', 'PROPERTY', False,
            (('display(binding)', 0), ('named(binding)', 0)), ())
    binding('other-owner-function', 'localOtherOwner', 'fun named(value: String): String = value', 'named', 'FUNCTION', False,
            (('named(binding)', 0),), ())
    binding('outer-function', 'localFunctions', 'fun named(value: String): String = value\n', 'named', 'FUNCTION', False,
            (('named(input)', 0), ('named(input)', 2)), (('named(input)', 1),))
    binding('shadowed-function', 'localFunctions', 'fun named(value: String): String = value + "inner"', 'named', 'FUNCTION', False,
            (('named(input)', 1),), (('named(input)', 0), ('named(input)', 2)))
    declaration_keys = {(item.declaration.startInclusive, item.declaration.endExclusive) for item in locals_}
    if len(declaration_keys) != len(locals_):
        raise FixtureIntegrityError('local declarations collapsed in source oracle')
    return SourceIntent(SOURCE, sha256(text.encode()).hexdigest(), tuple(flows), tuple(locals_))


def other_file_locals(text):
    """Authored cross-file control has one reference to each local declaration."""
    def located(fragment):
        if text.count(fragment) != 1:
            raise FixtureIntegrityError('cross-file authored fragment missing or duplicated')
        found = span(text, fragment)
        return SourceSpan(OTHER_SOURCE, found.startInclusive, found.endExclusive, found.text)
    owning = located('otherFileBindings')
    call = located('named(binding)')
    declared_property = located('val binding = input')
    declared_function = located('fun named(value: String): String = value')
    whole_owner = text[text.index('fun otherFileBindings('):].rstrip()
    owner_range = located(whole_owner)
    lexical = (located(whole_owner[whole_owner.index('{'):]),)
    return (
        LocalCase('other-file-val', owning, declared_property,
                  SourceSpan(OTHER_SOURCE, declared_property.startInclusive + 4,
                             declared_property.startInclusive + 11, 'binding'), 'PROPERTY', False,
                  (SourceSpan(OTHER_SOURCE, call.startInclusive + 6, call.startInclusive + 13, 'binding'),), (), owner_range, lexical),
        LocalCase('other-file-function', owning, declared_function,
                  SourceSpan(OTHER_SOURCE, declared_function.startInclusive + 4,
                             declared_function.startInclusive + 9, 'named'), 'FUNCTION', False,
                  (SourceSpan(OTHER_SOURCE, call.startInclusive, call.startInclusive + 5, 'named'),), (), owner_range, lexical),
    )


def load_oracle(source=TEMPLATE, other_source=OTHER_TEMPLATE, supplemental_source=None):
    import supplemental_trust_oracle as supplemental
    primary = materialize(source.read_text())
    other = other_source.read_text()
    flows, locals_, callables, inventory = supplemental.materialize(
        (supplemental_source or supplemental.TEMPLATE).read_text())
    return SourceIntent(primary.sourcePath, primary.sourceSha256, primary.flows + flows,
                        primary.locals + other_file_locals(other) + locals_,
                        (SourceInventory(OTHER_SOURCE, sha256(other.encode()).hexdigest()), inventory),
                        callables=(CallableIntent('Voltage.', span(source.read_text(), 'encrypt', start=source.read_text().index('object Voltage'))),
                                   CallableIntent('Hiped.', span(source.read_text(), 'encrypt', start=source.read_text().index('object Hiped')))) + callables)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=TEMPLATE)
    parser.add_argument('--other-source', type=Path, default=OTHER_TEMPLATE)
    parser.add_argument('--supplemental-source', type=Path)
    parser.add_argument('--scenario', choices=('fixture', 'supplemental'), default='fixture')
    args = parser.parse_args()
    if args.scenario == 'supplemental':
        import supplemental_trust_oracle as supplemental
        intent = supplemental.load_oracle(args.supplemental_source or supplemental.TEMPLATE)
    else:
        intent = load_oracle(args.source, args.other_source, args.supplemental_source)
    print(json.dumps(asdict(intent), indent=2))


if __name__ == '__main__':
    main()
