"""Exact independent producer, call-site, and capture oracle for returned values."""
from dataclasses import dataclass
from pathlib import Path

import immutable_callback_oracle as fixture
import static_callback_oracle as source
import qualify_callback_tracing as check


@dataclass(frozen=True)
class FactoryOracle:
    caller: source.SymbolOracle
    producer: source.SymbolOracle
    parameter: source.SourceSpan
    call: source.SourceSpan
    argument: source.SourceSpan
    returned: tuple[source.SourceSpan, ...]
    capture_target: source.SymbolOracle | None
    capture_invocation: source.SourceSpan | None
    branch_expression: source.SourceSpan | None = None
    branch_targets: tuple[source.SymbolOracle, ...] = ()
    capture_alias: tuple[source.SourceSpan, ...] = ()
    body_target: source.SymbolOracle | None = None
    body_call: source.SourceSpan | None = None


def factory_oracle(root, case_name):
    root = Path(root).resolve(strict=True)
    if case_name == 'factory-return-mutation':
        loaded = tuple(source.SourceDocument(name, (root / name).read_text()) for name in fixture.SOURCE_FILES)
        mutation = next(item for item in fixture.MUTATIONS if item.name == 'factory-return')
        restored = fixture.apply_edit(loaded, fixture.SourceEdit(mutation.edit.file, mutation.edit.after, mutation.edit.before))
        fixture.validate_sources(restored)
    else: loaded = fixture.load_sources(root)
    documents = {document.relative_path: document for document in loaded}
    def span(file, text, within=None):
        return source._span(str(root), documents[file], text, within)
    def child(parent, text):
        offset = source._unique_start(parent.text, text)
        start = parent.range.startInclusive + source._utf16_length(parent.text[:offset])
        return source.SourceSpan(parent.file, source.Utf16Range(start, start + source._utf16_length(text)), text)
    def symbol(file, name, declaration):
        where = span(file, declaration)
        name_site = span(file, name, declaration)
        package = documents[file].text.splitlines()[0].removeprefix('package ')
        return source.SymbolOracle(name, package + '.' + name, where, name_site)
    if case_name in ('capture-alpha', 'capture-beta', 'captured-alias', 'direct-returned-invocation', 'local-returned-invocation', 'factory-return-mutation'):
        suffix = 'Alpha' if case_name in ('capture-alpha', 'direct-returned-invocation', 'factory-return-mutation') else 'Beta'
        target = suffix.lower() + 'Target'
        alias = case_name == 'captured-alias'
        caller_name = 'capturedAliasEntry' if alias else 'captured' + suffix + 'Entry'
        producer_name = 'capturedAliasFactory' if alias else 'captureFactory'
        producer_text = ('fun capturedAliasFactory(block: () -> String): () -> String {\n    val saved = block\n    return { saved() }\n}'
                         if alias else 'fun captureFactory(block: () -> String): () -> String = { block() }')
        body = '{ saved() }' if alias else '{ block() }'
        mutated = case_name == 'factory-return-mutation'
        if mutated:
            producer_text = producer_text.replace('{ block() }', '{ betaTarget() }')
            body = '{ betaTarget() }'
        caller_text = f'fun {caller_name}(): String = wrapper({producer_name}(::{target}))'
        if case_name == 'direct-returned-invocation':
            caller_name = 'directReturnedEntry'
            caller_text = 'fun directReturnedEntry(): String = captureFactory(::alphaTarget)()'
        elif case_name == 'local-returned-invocation':
            caller_name = 'localReturnedEntry'
            caller_text = 'fun localReturnedEntry(): String {\n    val action = captureFactory(::betaTarget)\n    return action()\n}'
        producer = symbol(fixture.FORWARDING, producer_name, producer_text)
        caller = symbol(fixture.SUPPLIERS, caller_name, caller_text)
        returned = span(fixture.FORWARDING, body, producer_text)
        local = span(fixture.FORWARDING, 'val saved = block', producer_text) if alias else None
        return FactoryOracle(caller, producer, span(fixture.FORWARDING, 'block: () -> String', producer_text),
            span(fixture.SUPPLIERS, f'{producer_name}(::{target})', caller_text),
            span(fixture.SUPPLIERS, '::' + target, caller_text),
            (returned,),
            symbol(fixture.INVOCATION, target, f'fun {target}(): String = "{suffix.lower()}"'),
            None if mutated else child(returned, 'saved()' if alias else 'block()'),
            capture_alias=(child(local, 'block'), local, child(returned, 'saved')) if alias else (),
            body_target=symbol(fixture.INVOCATION, 'betaTarget', 'fun betaTarget(): String = "beta"') if mutated else None,
            body_call=child(returned, 'betaTarget()') if mutated else None)
    if case_name == 'branch-alternatives':
        declaration = 'fun choiceFactory(first: Boolean): () -> String =\n    if (first) ::alphaTarget else ::betaTarget'
        caller = 'fun branchEntry(first: Boolean): String = wrapper(choiceFactory(first))'
        return FactoryOracle(symbol(fixture.SUPPLIERS, 'branchEntry', caller),
            symbol(fixture.FORWARDING, 'choiceFactory', declaration),
            span(fixture.FORWARDING, 'first: Boolean', declaration),
            span(fixture.SUPPLIERS, 'choiceFactory(first)', caller),
            span(fixture.SUPPLIERS, 'first', 'choiceFactory(first)'),
            tuple(span(fixture.FORWARDING, '::' + target, declaration) for target in ('alphaTarget', 'betaTarget')),
            None, None,
            span(fixture.FORWARDING, 'if (first) ::alphaTarget else ::betaTarget', declaration),
            tuple(symbol(fixture.INVOCATION, target, f'fun {target}(): String = "{target.removesuffix("Target")}"')
                  for target in ('alphaTarget', 'betaTarget')))
    raise ValueError('no authored factory oracle: ' + case_name)


def impact_site(actual, expected):
    assert actual['range'] == {'start': expected.range.startInclusive, 'end': expected.range.endExclusive}, (actual, expected)


def declaration(actual, expected, identity):
    assert actual['file'] == expected.file
    impact_site(actual, expected)
    assert actual['compilerIdentity'] == identity


def callable_identity(actual, expected):
    check.assert_callable(actual, expected)
    return actual['compiler_target']['compiler_evidence']['identity']


def assert_factory(factory, expected):
    producer_id = callable_identity(factory['callable'], expected.producer)
    assert len(factory['captures']) == 1, 'factory parameter inventory changed'
    capture, = factory['captures']
    binding = capture['binding']
    assert binding['type'] == 'BOUND'
    check.assert_formal(binding, source.FormalOracle(expected.producer, expected.parameter, 0))
    assert binding['invocation_owner']['type'] == 'NAMED'
    caller_id = callable_identity(binding['invocation_owner']['callable'], expected.caller)
    declaration(factory['enclosing'], expected.caller.declaration, caller_id)
    impact_site(factory['invocation'], expected.call)
    declaration(factory['invocation']['callable'], expected.producer.declaration, producer_id)
    assert binding['invocation'] == factory['invocation']
    check.assert_span(binding['invocation_occurrence'], expected.call)
    selection = capture['selection']
    assert selection['type'] == 'EXPLICIT', 'factory capture selection changed'
    argument = selection['argument']
    declaration(argument['enclosing'], expected.caller.declaration, caller_id)
    impact_site(argument, expected.argument)
    assert argument['role'] == {'type': 'ARGUMENT', 'invocation': factory['invocation'], 'index': 0}
    returned = factory['returned_value']
    declaration(returned['source']['enclosing'], expected.producer.declaration, producer_id)
    declaration(returned['destination']['enclosing'], expected.producer.declaration, producer_id)
    if expected.capture_target is not None:
        assert returned['origin']['type'] == 'ANONYMOUS'
        body = returned['origin']['body']
        assert body['type'] == 'ANONYMOUS'
        check.assert_span(body['occurrence'], expected.returned[0])
        impact_site(returned['source'], expected.returned[0])
        assert returned['source'] == returned['destination'] and returned['transfers'] == []
        assert returned['invoked_callables'] == []
        content = capture['content']
        assert content['type'] == 'CALLABLE' and len(content['values']) == 1
        value, = content['values']
        assert value['origin']['type'] == 'NAMED'
        origin = value['origin']
        callable_identity(origin['target'], expected.capture_target)
        check.assert_span(origin['occurrence'], expected.argument)
        assert origin['dispatch_receiver'] == {'type': 'ABSENT'} and origin['extension_receiver'] == {'type': 'ABSENT'}
        declaration(value['source']['enclosing'], expected.caller.declaration, caller_id)
        impact_site(value['source'], expected.argument)
        assert value['source']['role'] == {'type': 'EXPRESSION_RESULT'}
        assert value['destination'] == argument
        assert value['transfers'] == [{'source': value['source'], 'target': argument, 'kind': 'ARGUMENT'}]
        assert len(value['invoked_callables']) == 1
        callable_identity(value['invoked_callables'][0], expected.producer)
        if expected.body_target is not None:
            assert content['invocations'] == []
            inventory = factory['body_calls']
            assert inventory['type'] == 'EXHAUSTIVE' and inventory['body'] == body
            assert len(inventory['calls']) == 1
            call, = inventory['calls']
            assert call['type'] == 'NAMED'
            check.assert_span(call['occurrence'], expected.body_call)
            callable_identity(call['target'], expected.body_target)
            return
        assert len(content['invocations']) == 1
        invocation, = content['invocations']
        assert factory['body_calls'] == {'type': 'EXHAUSTIVE', 'body': body, 'calls': [{
            'type': 'CAPTURED', 'invocation': invocation,
            'formal': {key: binding[key] for key in ('callable', 'parameter', 'position')}}]}
        check.assert_span(invocation['occurrence'], expected.capture_invocation)
        assert invocation['owner'] == body, 'capture invocation changed returned closure owner'
        assert invocation['forwardings'] == []
        transfers = invocation['callable_transfers']
        if expected.capture_alias:
            assert len(transfers) == 2 and [edge['kind'] for edge in transfers] == ['LOCAL_BINDING', 'LOCAL_READ']
            assert transfers[0]['target'] == transfers[1]['source']
            sites = (transfers[0]['source'], transfers[0]['target'], transfers[1]['target'])
            for site, span, role in zip(sites, expected.capture_alias, ('EXPRESSION_RESULT', 'LOCAL_BINDING', 'LOCAL_READ')):
                declaration(site['enclosing'], expected.producer.declaration, producer_id)
                impact_site(site, span)
                assert site['role'] == {'type': role}
        else:
            assert transfers == []
    else:
        assert factory['body_calls'] == {'type': 'NOT_APPLICABLE'}
        assert capture['content'] == {'type': 'SCALAR'}
        assert returned['origin']['type'] == 'NAMED'
        origin = returned['origin']
        alternatives = [(site, target) for site, target in zip(expected.returned, expected.branch_targets)
                        if check.site_key(origin['occurrence']) == (site.file, site.range.startInclusive, site.range.endExclusive)]
        assert len(alternatives) == 1, 'factory branch source changed'
        occurrence, target = alternatives[0]
        callable_identity(origin['target'], target)
        assert origin['dispatch_receiver'] == {'type': 'ABSENT'} and origin['extension_receiver'] == {'type': 'ABSENT'}
        impact_site(returned['source'], occurrence)
        impact_site(returned['destination'], expected.branch_expression)
        assert returned['source']['role'] == returned['destination']['role'] == {'type': 'EXPRESSION_RESULT'}
        assert returned['transfers'] == [{'source': returned['source'], 'target': returned['destination'], 'kind': 'BRANCH_ALTERNATIVE'}]
        assert returned['invoked_callables'] == []


def assert_factory_contexts(root, case, pages):
    expected = factory_oracle(root, case.name)
    tables = []
    def visit(value):
        if isinstance(value, dict):
            if 'factories' in value and value['factories']:
                tables.append(value['factories'])
            for child in value.values(): visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    for page in pages: visit(page['relation_observations'])
    assert tables, 'returned callback has no factory context'
    for table in tables:
        assert len(table) == 1, 'fixture has exactly one factory per returned alternative'
        assert_factory(table[0], expected)


def assert_transparent_return(root, pages):
    """A transparent formal return must retain the exact argument and return hop."""
    root = Path(root).resolve(strict=True)
    documents = {document.relative_path: document for document in fixture.load_sources(root)}
    def span(file, text, within=None): return source._span(str(root), documents[file], text, within)
    def symbol(file, name, declaration):
        package = documents[file].text.splitlines()[0].removeprefix('package ')
        return source.SymbolOracle(name, package + '.' + name, span(file, declaration), span(file, name, declaration))
    caller_text = 'fun identityReturnedEntry(): String = wrapper(identityFactory(::alphaTarget))'
    caller = symbol(fixture.SUPPLIERS, 'identityReturnedEntry', caller_text)
    producer = symbol(fixture.FORWARDING, 'identityFactory', 'fun identityFactory(block: () -> String): () -> String = block')
    target = symbol(fixture.INVOCATION, 'alphaTarget', 'fun alphaTarget(): String = "alpha"')
    call = span(fixture.SUPPLIERS, 'identityFactory(::alphaTarget)', caller_text)
    argument = span(fixture.SUPPLIERS, '::alphaTarget', caller_text)
    values = []
    def visit(value):
        if isinstance(value, dict):
            if 'factories' in value and any(edge.get('kind') == 'WRAPPER_RETURN' for edge in value['transfers']): values.append(value)
            for child in value.values(): visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    for page in pages: visit(page['relation_observations'])
    assert values, 'transparent return lost its compiler transfer chain'
    for value in values:
        assert value['factories'] == [] and value['origin']['type'] == 'NAMED'
        callable_identity(value['origin']['target'], target)
        check.assert_span(value['origin']['occurrence'], argument)
        assert value['origin']['dispatch_receiver'] == value['origin']['extension_receiver'] == {'type': 'ABSENT'}
        first, returned = value['transfers'][:2]
        assert first['kind'] == 'ARGUMENT' and returned['kind'] == 'WRAPPER_RETURN'
        assert first['source'] == value['source'] and first['target'] == returned['source']
        bound, result = first['target'], returned['target']
        assert bound['role']['type'] == 'ARGUMENT' and bound['role']['index'] == 0
        assert first['source']['role'] == result['role'] == {'type': 'EXPRESSION_RESULT'}
        for site in (first['source'], bound): impact_site(site, argument)
        impact_site(result, call)
        invocation = bound['role']['invocation']
        impact_site(invocation, call)
        matched = [item for item in value['invoked_callables'] if check.site_key(item['declaration']) ==
                   (producer.declaration.file, producer.declaration.range.startInclusive, producer.declaration.range.endExclusive)]
        assert len(matched) == 1, 'transparent producer evidence missing or duplicated'
        producer_id = callable_identity(matched[0], producer)
        declaration(invocation['callable'], producer.declaration, producer_id)
        # The exact caller signature is common to all sites and is separately present on the supplied binding.
        for site in (first['source'], bound, result):
            assert site['enclosing']['file'] == caller.declaration.file
            impact_site(site['enclosing'], caller.declaration)
        assert first['source']['enclosing'] == bound['enclosing'] == result['enclosing']
