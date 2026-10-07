"""Source-authored factory evidence checks; synthetic documents prove reader behavior."""
import copy
from dataclasses import asdict
from hashlib import sha256
from pathlib import Path
import unittest
import shutil
from tempfile import TemporaryDirectory

import immutable_factory_qualification as q
import immutable_callback_qualification as values


def occurrence(span): return {'file': span.file, 'range': asdict(span.range)}
def region(span): return {'start': span.range.startInclusive, 'end': span.range.endExclusive}
def identity(symbol): return 'canonical-signature-sha256-v1|' + sha256(symbol.fqn.encode()).hexdigest()
def callable(symbol):
    return {'declaration': occurrence(symbol.declaration), 'compiler_target': {
        'file': symbol.declaration.file, 'range': asdict(symbol.declaration.range), 'name': symbol.name,
        'compiler_evidence': {'identity': identity(symbol), 'signature': {'qualifiedIdentity': symbol.fqn}}}}
def declaration(symbol):
    return {'file': symbol.declaration.file, 'range': region(symbol.declaration), 'compilerIdentity': identity(symbol)}
def site(span, owner, role): return {'enclosing': declaration(owner), 'range': region(span), 'role': role}


def factory(expected):
    call = {'callable': declaration(expected.producer), 'range': region(expected.call)}
    binding = {'type': 'BOUND', 'callable': callable(expected.producer), 'parameter': occurrence(expected.parameter),
        'position': 0, 'invocation': call, 'invocation_occurrence': occurrence(expected.call),
        'invocation_owner': {'type': 'NAMED', 'callable': callable(expected.caller)}}
    argument = site(expected.argument, expected.caller, {'type': 'ARGUMENT', 'invocation': call, 'index': 0})
    source = site(expected.argument, expected.caller, {'type': 'EXPRESSION_RESULT'})
    if expected.capture_target is None:
        source = site(expected.returned[0], expected.producer, {'type': 'EXPRESSION_RESULT'})
        destination = site(expected.branch_expression, expected.producer, {'type': 'EXPRESSION_RESULT'})
        returned = {'origin': {'type': 'NAMED', 'target': callable(expected.branch_targets[0]),
            'occurrence': occurrence(expected.returned[0]), 'dispatch_receiver': {'type': 'ABSENT'},
            'extension_receiver': {'type': 'ABSENT'}}, 'source': source, 'destination': destination,
            'transfers': [{'source': source, 'target': destination, 'kind': 'BRANCH_ALTERNATIVE'}], 'invoked_callables': []}
        return {'enclosing': declaration(expected.caller), 'invocation': call, 'callable': callable(expected.producer),
            'returned_value': returned, 'body_calls': {'type': 'NOT_APPLICABLE'}, 'captures': [{'binding': binding,
                'selection': {'type': 'EXPLICIT', 'argument': argument}, 'content': {'type': 'SCALAR'}}]}
    captured = {'origin': {'type': 'NAMED', 'target': callable(expected.capture_target),
        'occurrence': occurrence(expected.argument), 'dispatch_receiver': {'type': 'ABSENT'},
        'extension_receiver': {'type': 'ABSENT'}}, 'source': source, 'destination': argument,
        'transfers': [{'source': source, 'target': argument, 'kind': 'ARGUMENT'}],
        'invoked_callables': [callable(expected.producer)]}
    returned_site = site(expected.returned[0], expected.producer, {'type': 'EXPRESSION_RESULT'})
    body = {'type': 'ANONYMOUS', 'occurrence': occurrence(expected.returned[0])}
    invocation = {'occurrence': occurrence(expected.capture_invocation or expected.body_call), 'owner': body,
        'callable_transfers': [], 'forwardings': []}
    if expected.capture_alias:
        sites = [site(span, expected.producer, {'type': role}) for span, role in
                 zip(expected.capture_alias, ('EXPRESSION_RESULT', 'LOCAL_BINDING', 'LOCAL_READ'))]
        invocation['callable_transfers'] = [{'source': sites[0], 'target': sites[1], 'kind': 'LOCAL_BINDING'},
                                          {'source': sites[1], 'target': sites[2], 'kind': 'LOCAL_READ'}]
    result = {'enclosing': declaration(expected.caller), 'invocation': call, 'callable': callable(expected.producer),
        'returned_value': {'origin': {'type': 'ANONYMOUS', 'body': body}, 'source': returned_site,
            'destination': returned_site, 'transfers': [], 'invoked_callables': []},
        'body_calls': {'type': 'EXHAUSTIVE', 'body': body, 'calls': [{'type': 'CAPTURED', 'invocation': invocation,
            'formal': {key: binding[key] for key in ('callable', 'parameter', 'position')}}]},
        'captures': [{'binding': binding, 'selection': {'type': 'EXPLICIT', 'argument': argument},
            'content': {'type': 'CALLABLE', 'values': [captured], 'invocations': [invocation]}}]}

    if expected.body_target is not None:
        result['captures'][0]['content']['invocations'] = []
        result['body_calls']['calls'] = [{'type': 'NAMED', 'occurrence': occurrence(expected.body_call),
                                        'target': callable(expected.body_target)}]
    return result


class ImmutableFactoryQualificationTest(unittest.TestCase):
    root = Path(__file__).parent / 'immutable-callback-fixture'

    def test_same_producer_keeps_two_independent_factory_calls_and_captured_targets(self):
        alpha = q.factory_oracle(self.root, 'capture-alpha')
        beta = q.factory_oracle(self.root, 'capture-beta')
        self.assertEqual(alpha.producer, beta.producer)
        self.assertNotEqual(alpha.caller, beta.caller)
        self.assertNotEqual(alpha.call, beta.call)
        self.assertNotEqual(alpha.argument, beta.argument)
        self.assertNotEqual(alpha.capture_target, beta.capture_target)
        q.assert_factory(factory(alpha), alpha)
        q.assert_factory(factory(beta), beta)
        with self.assertRaises(AssertionError): q.assert_factory(factory(beta), alpha)
        for name in ('direct-returned-invocation', 'local-returned-invocation'):
            expected = q.factory_oracle(self.root, name)
            q.assert_factory(factory(expected), expected)
            self.assertEqual(alpha.producer, expected.producer)
            self.assertNotEqual(alpha.caller, expected.caller)

    def test_returned_body_call_inventory_is_required_and_named_calls_drive_targets(self):
        expected = q.factory_oracle(self.root, 'capture-alpha')
        observed = factory(expected)
        value = {'origin': {'type': 'RETURNED', 'factory_index': 0}, 'source': 'result', 'destination': 'result',
                 'transfers': [], 'invoked_callables': [], 'factories': [observed]}
        self.assertEqual(('alphaTarget',), values.value_proof(value))
        for change in ('missing', 'empty', 'wrong-owner', 'wrong-formal', 'duplicate'):
            changed = copy.deepcopy(value); inventory = changed['factories'][0]['body_calls']
            if change == 'missing': del changed['factories'][0]['body_calls']
            elif change == 'empty': inventory['calls'].clear()
            elif change == 'wrong-owner': inventory['body']['occurrence']['file'] += 'foreign'
            elif change == 'wrong-formal': inventory['calls'][0]['formal']['position'] = 1
            else: inventory['calls'] *= 2
            with self.subTest(change=change), self.assertRaises(values.ProofRejected): values.value_proof(changed)
        target = q.factory_oracle(self.root, 'capture-beta').capture_target
        observed['captures'][0]['content']['invocations'].clear()
        observed['body_calls']['calls'] = [{'type': 'NAMED', 'occurrence': occurrence(expected.capture_invocation or expected.body_call),
                                          'target': callable(target)}]
        self.assertEqual(('betaTarget',), values.value_proof(value))
        # Merely supplying alpha does not make alpha invoked after the body changed.
        self.assertNotIn('alphaTarget', values.value_proof(value))
        observed['body_calls']['calls'][0]['occurrence']['range']['startInclusive'] -= 100
        with self.assertRaises(values.ProofRejected): values.value_proof(value)

    def test_named_returned_body_mutation_has_exact_source_site_and_unused_capture(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary) / 'fixture'
            shutil.copytree(self.root, root)
            mutation = next(item for item in q.fixture.MUTATIONS if item.name == 'factory-return')
            file = root / mutation.edit.file
            file.write_text(file.read_text().replace(mutation.edit.before, mutation.edit.after))
            expected = q.factory_oracle(root, 'factory-return-mutation')
            observed = factory(expected)
            q.assert_factory(observed, expected)
            for change in ('target', 'site', 'capture'):
                changed = copy.deepcopy(observed)
                if change == 'target': changed['body_calls']['calls'][0]['target'] = callable(expected.capture_target)
                elif change == 'site': changed['body_calls']['calls'][0]['occurrence']['range']['startInclusive'] += 1
                else: changed['captures'][0]['content']['invocations'] = [{'unproven': 'activation'}]
                with self.subTest(change=change), self.assertRaises(AssertionError): q.assert_factory(changed, expected)

    def test_context_truncation_rebinding_and_producer_substitution_reject(self):
        expected = q.factory_oracle(self.root, 'capture-alpha')
        for mutation in ('call', 'producer', 'capture', 'argument', 'target', 'returned-owner', 'invocation-owner', 'transfer'):
            value = copy.deepcopy(factory(expected))
            capture = value['captures'][0]
            if mutation == 'call': value['invocation']['range']['start'] += 1
            if mutation == 'producer': value['callable']['compiler_target']['compiler_evidence']['signature']['qualifiedIdentity'] += 'Other'
            if mutation == 'capture': value['captures'].clear()
            if mutation == 'argument': capture['selection']['argument']['range']['end'] -= 1
            if mutation == 'target': capture['content']['values'][0]['origin']['target']['compiler_target']['name'] = 'betaTarget'
            if mutation == 'returned-owner': value['returned_value']['source']['enclosing']['file'] += 'foreign'
            if mutation == 'invocation-owner': capture['content']['invocations'][0]['owner'] = {'type': 'NAMED'}
            if mutation == 'transfer': capture['content']['values'][0]['transfers'].clear()
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): q.assert_factory(value, expected)

    def test_captured_alias_keeps_exact_formal_read_binding_and_closure_read(self):
        expected = q.factory_oracle(self.root, 'captured-alias')
        observed = factory(expected)
        q.assert_factory(observed, expected)
        transfers = observed['captures'][0]['content']['invocations'][0]['callable_transfers']
        self.assertEqual(['LOCAL_BINDING', 'LOCAL_READ'], [edge['kind'] for edge in transfers])
        transfers[0]['source']['range']['start'] += 1
        with self.assertRaises(AssertionError): q.assert_factory(observed, expected)

    def test_branch_target_remains_bound_to_its_exact_producer_source(self):
        expected = q.factory_oracle(self.root, 'branch-alternatives')
        observed = factory(expected)
        q.assert_factory(observed, expected)
        observed['returned_value']['origin']['target'] = callable(expected.branch_targets[1])
        with self.assertRaises(AssertionError): q.assert_factory(observed, expected)

    def test_factory_table_rejects_missing_unused_forward_or_cyclic_context(self):
        expected = q.factory_oracle(self.root, 'capture-alpha')
        source = site(expected.call, expected.caller, {'type': 'EXPRESSION_RESULT'})
        value = {'origin': {'type': 'RETURNED', 'factory_index': 0}, 'source': source, 'destination': source,
                 'transfers': [], 'invoked_callables': [], 'factories': [factory(expected)]}
        self.assertEqual(('alphaTarget',), values.value_proof(value))
        for mutation in ('missing', 'unused', 'cycle', 'negative', 'bool'):
            broken = copy.deepcopy(value)
            if mutation == 'missing': broken['factories'].clear()
            if mutation == 'unused': broken['factories'].append(copy.deepcopy(broken['factories'][0]))
            if mutation == 'cycle': broken['factories'][0]['returned_value']['origin'] = {'type': 'RETURNED', 'factory_index': 0}
            if mutation == 'negative': broken['origin']['factory_index'] = -1
            if mutation == 'bool': broken['origin']['factory_index'] = True
            with self.subTest(mutation=mutation), self.assertRaises(values.ProofRejected): values.value_proof(broken)

    def test_transparent_return_retains_argument_and_producer_return_sites(self):
        documents = {item.relative_path: item for item in q.fixture.load_sources(self.root)}
        def span(file, text, within=None): return q.source._span(str(self.root.resolve()), documents[file], text, within)
        def symbol(file, name, text):
            package = documents[file].text.splitlines()[0].removeprefix('package ')
            return q.source.SymbolOracle(name, package + '.' + name, span(file, text), span(file, name, text))
        caller_text = 'fun identityReturnedEntry(): String = wrapper(identityFactory(::alphaTarget))'
        caller = symbol(q.fixture.SUPPLIERS, 'identityReturnedEntry', caller_text)
        producer = symbol(q.fixture.FORWARDING, 'identityFactory', 'fun identityFactory(block: () -> String): () -> String = block')
        target = symbol(q.fixture.INVOCATION, 'alphaTarget', 'fun alphaTarget(): String = "alpha"')
        argument = span(q.fixture.SUPPLIERS, '::alphaTarget', caller_text)
        call = span(q.fixture.SUPPLIERS, 'identityFactory(::alphaTarget)', caller_text)
        invocation = {'callable': declaration(producer), 'range': region(call)}
        start = site(argument, caller, {'type': 'EXPRESSION_RESULT'})
        bound = site(argument, caller, {'type': 'ARGUMENT', 'index': 0, 'invocation': invocation})
        end = site(call, caller, {'type': 'EXPRESSION_RESULT'})
        value = {'origin': {'type': 'NAMED', 'target': callable(target), 'occurrence': occurrence(argument),
            'dispatch_receiver': {'type': 'ABSENT'}, 'extension_receiver': {'type': 'ABSENT'}},
            'source': start, 'destination': end, 'factories': [], 'invoked_callables': [callable(producer)],
            'transfers': [{'source': start, 'target': bound, 'kind': 'ARGUMENT'},
                          {'source': bound, 'target': end, 'kind': 'WRAPPER_RETURN'}]}
        page = {'relation_observations': [value]}
        q.assert_transparent_return(self.root, [page])
        value['transfers'][1]['target']['range']['start'] += 1
        with self.assertRaises(AssertionError): q.assert_transparent_return(self.root, [page])


if __name__ == '__main__': unittest.main()
