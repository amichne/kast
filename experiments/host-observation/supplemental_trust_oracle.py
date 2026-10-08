"""Literal Unit/Nothing results and compiler-owned enum-entry locals; no Kast observations."""

from dataclasses import replace
from hashlib import sha256

import try_local_oracle as oracle


SOURCE = 'logging/src/main/kotlin/representation/supplemental/SupplementalTrustFixture.kt'
TEMPLATE = oracle.TEMPLATE.with_name('SupplementalTrustFixture.kt')


def materialize(text):
    def located(fragment):
        if text.count(fragment) != 1:
            raise oracle.FixtureIntegrityError('supplemental literal missing or duplicated')
        return replace(oracle.span(text, fragment), file=SOURCE)

    flows = []
    callables = []
    specs = (
        ('unit-produced', 'tryFinalUnit', 'unitProducer', 'Unit', 'Unit',
         oracle.CompletionExpectation.UNIT_UNSUPPORTED),
        ('nothing-produced', 'tryFinalNothing', 'bottomProducer', 'Nothing', 'throw failure',
         oracle.CompletionExpectation.ABRUPT_COMPLETION),
        ('nullable-nothing-produced', 'tryFinalNullableBottom', 'nullableBottomProducer', 'Nothing?', 'null',
         oracle.CompletionExpectation.NORMAL_RETURN),
    )
    for name, function, producer, result_type, fallback, expectation in specs:
        call = producer + '()'
        branch = '{\n    ' + call + '\n}'
        result = 'try ' + branch + ' catch (failure: IllegalStateException) {\n    ' + fallback + '\n}'
        owner = 'fun ' + function + '(): ' + result_type + ' = ' + result
        whole = located(owner)
        name_site = replace(whole, startInclusive=whole.startInclusive + 4,
                            endExclusive=whole.startInclusive + 4 + len(function), text=function)
        normal = expectation == oracle.CompletionExpectation.NORMAL_RETURN
        results = (oracle.BranchResult(located(result), located(branch), 'TRY'),) if normal else ()
        start = text.index(owner)
        producer_site = replace(oracle.span(text, call, start=start, end=start + len(owner)), file=SOURCE)
        flows.append(oracle.FlowCase(name, name_site, producer_site, expectation, int(normal),
                                     'TRY' if normal else 'NONE', (), results,
                                     (located(result),) if normal else ()))
        declaration = located('fun ' + producer + '(): ' + result_type)
        callables.append(oracle.CallableIntent(producer + '(', replace(
            declaration, startInclusive=declaration.startInclusive + 4,
            endExclusive=declaration.startInclusive + 4 + len(producer), text=producer)))

    locals_ = []
    entry_refs = {}
    for entry, expression in (('FIRST', 'binding + value'), ('SECOND', 'value + binding')):
        owner = ('override fun compute(input: String): String {\n'
                 '            val binding = input\n'
                 '            fun named(value: String): String = ' + expression + '\n'
                 '            return named(binding)\n        }')
        whole = located(owner)
        body = located(owner[owner.index('{'):])
        owning = replace(whole, startInclusive=whole.startInclusive + len('override fun '),
                         endExclusive=whole.startInclusive + len('override fun compute'), text='compute')
        begin = text.index(owner)

        def within(fragment):
            return replace(oracle.span(text, fragment, start=begin, end=begin + len(owner)), file=SOURCE)

        binding = within('val binding = input')
        named = within('fun named(value: String): String = ' + expression)
        captured = within(expression)
        captured = replace(captured, startInclusive=captured.startInclusive + expression.index('binding'),
                           endExclusive=captured.startInclusive + expression.index('binding') + 7, text='binding')
        call = within('named(binding)')
        property_refs = (captured, replace(call, startInclusive=call.startInclusive + 6,
                                          endExclusive=call.startInclusive + 13, text='binding'))
        function_refs = (replace(call, endExclusive=call.startInclusive + 5, text='named'),)
        entry_refs[entry] = (property_refs, function_refs)
        for kind, declaration, name, refs in (
                ('PROPERTY', binding, 'binding', property_refs), ('FUNCTION', named, 'named', function_refs)):
            name_site = replace(declaration, startInclusive=declaration.startInclusive + 4,
                                endExclusive=declaration.startInclusive + 4 + len(name), text=name)
            locals_.append(oracle.LocalCase('enum-' + entry.lower() + '-' + kind.lower(), owning,
                                           declaration, name_site, kind, False, refs, (), whole, (body,)))
    for index, local in enumerate(locals_):
        other = 'SECOND' if index < 2 else 'FIRST'
        locals_[index] = replace(local, forbiddenReferences=entry_refs[other][index % 2])
    return tuple(flows), tuple(locals_), tuple(callables), oracle.SourceInventory(SOURCE, sha256(text.encode()).hexdigest())


def load_oracle(source=TEMPLATE):
    flows, locals_, callables, inventory = materialize(source.read_text())
    return oracle.SourceIntent(SOURCE, inventory.sha256, flows, locals_, scenario='supplemental',
                               sourceDirectory='logging', callables=callables)
