"""Independent native-response reader checks, not compiler proof."""
import copy
import unittest

import immutable_callback_oracle as oracle
import immutable_callback_qualification as q
import qualify_immutable_callbacks as runner


def callable(name):
    return {'compiler_target': {'compiler_evidence': {'signature': {'qualifiedIdentity': 'fixture.immutable.invocation.' + name}}}}


def graph():
    formal = {'callable': callable('wrapper'), 'position': 0, 'parameter': 'authored-formal'}
    return {'type': 'EXHAUSTED_GRAPH', 'root': formal, 'formals': [formal], 'forwardings': []}


def value(name='alphaTarget'):
    return {'origin': {'type': 'NAMED', 'target': callable(name)}, 'source': 'authored-origin',
            'destination': 'authored-argument', 'factories': [], 'transfers': [{'source': 'authored-origin', 'target': 'authored-argument'}]}


def page(flow, name='alphaTarget'):
    return {'status': 'complete', 'coverage': {'exhaustive': True}, 'relation_observations': [{
        'callback_observations': [], 'callable_observations': [{'target': {'type': 'NAMED_REFERENCE',
            'reference': {'target': callable(name), 'flow': flow}}}]}]}


def supplied(selection='EXPLICIT', name='alphaTarget', owner='referenceEntry'):
    return {'type': 'SUPPLIED', 'supplier': {'value': value(name), 'selection': {'type': selection},
            'binding': {'invocation_owner': {'callable': callable(owner)}}},
            'forwarding': graph(), 'invocations': ['authored-invocation']}


def immutable(use):
    return {'type': 'IMMUTABLE', 'flow': {'scan': 'EXHAUSTIVE', 'obligations': [],
            'source_value': value(), 'uses': [use]}}


class ImmutableCallbackQualificationTest(unittest.TestCase):
    def case(self, name):
        return next(case for case in oracle.CASES if case.name == name)

    def test_complete_named_rows_without_callback_evidence_do_not_qualify(self):
        document = page(immutable(supplied()))
        document['relation_observations'][0]['callable_observations'] = []
        for name in ('default-omission', 'branch-alternatives', 'formal-supplier-inventory', 'formal-empty-inventory'):
            with self.subTest(name=name), self.assertRaises(q.ProofRejected) as rejected:
                q.qualify_case(self.case(name), [document])
            self.assertEqual(q.QualificationFailure.MISSING_EVIDENCE, rejected.exception.cause)

    def test_immutable_value_keeps_target_and_connected_transfer_proof(self):
        document = page(immutable(supplied()))
        result = q.qualify_case(self.case('reference'), [document])
        self.assertEqual(('alphaTarget',), result.targets)
        broken = copy.deepcopy(document)
        broken['relation_observations'][0]['callable_observations'][0]['target']['reference']['flow']['flow']['uses'][0]['supplier']['value']['transfers'][0]['source'] = 'foreign-origin'
        with self.assertRaises(q.ProofRejected) as rejected:
            q.qualify_case(self.case('reference'), [broken])
        self.assertEqual(q.QualificationFailure.DISCONNECTED_TRANSFER, rejected.exception.cause)

    def test_default_omission_requires_default_selection_and_override_excludes_it(self):
        q.qualify_case(self.case('default-omission'), [page(immutable(supplied('DEFAULT')))])
        with self.assertRaises(q.ProofRejected):
            q.qualify_case(self.case('default-omission'), [page(immutable(supplied()))])
        with self.assertRaises(q.ProofRejected):
            q.qualify_case(self.case('default-override'), [page(immutable(supplied('DEFAULT', 'betaTarget')))])

    def test_branch_must_preserve_both_independent_targets(self):
        pages = [page(immutable(supplied(name='alphaTarget'))), page(immutable(supplied(name='betaTarget')), 'betaTarget')]
        q.qualify_case(self.case('direct-branch'), pages)
        with self.assertRaises(q.ProofRejected):
            q.qualify_case(self.case('direct-branch'), pages[:1])

    def test_selected_supplies_require_all_formals_and_actual_invocation_before_counting_target(self):
        formal = graph()['root']
        use = supplied('DEFAULT')
        del use['type']
        use['supplier']['binding'].update(formal)
        document = page(immutable(use))
        target = {'type': 'CALLBACK_SUPPLIES', 'formals': [formal], 'supplies': [use]}
        document['relation_observations'][0]['callable_observations'] = [{'target': target}]
        q.qualify_case(self.case('default-omission'), [document])
        use['invocations'] = []
        q.qualify_case(self.case('stored-not-invoked'), [document])
        with self.assertRaises(q.ProofRejected): q.qualify_case(self.case('default-omission'), [document])
        for extra in (dict(formal, position=1), formal):
            target['formals'].append(extra)
            with self.assertRaises(q.ProofRejected): q.qualify_case(self.case('stored-not-invoked'), [document])
            target['formals'].pop()

    def test_incomplete_or_qualified_flow_does_not_become_complete(self):
        for mutation in ('status', 'coverage', 'scan', 'obligation', 'unknown'):
            document = page(immutable(supplied()))
            flow = document['relation_observations'][0]['callable_observations'][0]['target']['reference']['flow']
            if mutation == 'status': document['status'] = 'partial'
            if mutation == 'coverage': document['coverage']['exhaustive'] = False
            if mutation == 'scan': flow['flow']['scan'] = 'INCOMPLETE'
            if mutation == 'obligation': flow['flow']['obligations'] = ['STORED_CALLBACK']
            if mutation == 'unknown': flow['type'] = 'UNAVAILABLE'
            with self.subTest(mutation=mutation), self.assertRaises(q.ProofRejected):
                q.qualify_case(self.case('reference'), [document])

    def test_direct_invocation_keeps_actual_call_and_owner_with_concrete_value(self):
        use = {'value': value(), 'binding': {'type': 'DIRECT', 'occurrence': 'actual-call', 'owner': 'actual-owner'}}
        callback = {'occurrence': 'actual-call', 'body': 'actual-owner', 'target': {'type': 'DIRECT_INVOCATIONS', 'invocations': [use]}}
        document = page(immutable(supplied()))
        document['relation_observations'][0]['callable_observations'] = [callback]
        q.qualify_case(self.case('reference'), [document])
        for field in ('occurrence', 'owner'):
            before = use['binding'][field]
            use['binding'][field] = 'foreign'
            with self.assertRaises(q.ProofRejected): q.qualify_case(self.case('reference'), [document])
            use['binding'][field] = before
        use['type'] = 'DIRECT'
        with self.assertRaises(q.ProofRejected): q.qualify_case(self.case('reference'), [document])

    def test_formal_inventory_requires_exact_two_supplier_owners_or_proven_empty(self):
        formal = graph()['root']
        suppliers = [supplied(name='alphaTarget', owner='knownAlphaSupplier')['supplier'],
                     supplied(name='betaTarget', owner='knownBetaSupplier')['supplier']]
        inventory = {'type': 'EXHAUSTIVE', 'root': formal, 'partitions': [
            {'formal': formal, 'suppliers': suppliers, 'incoming': []}]}
        document = page(immutable(supplied()))
        document['relation_observations'][0]['callable_observations'] = [
            {'occurrence': 'formal-call', 'body': 'formal-owner', 'target': {'type': 'PARAMETER_INVOCATION',
                'parameter': formal, 'suppliers': inventory, 'invocation': {'occurrence': 'formal-call',
                    'owner': 'formal-owner', 'callable_transfers': [], 'forwardings': []}}}]
        q.qualify_case(self.case('formal-supplier-inventory'), [document])
        suppliers.append(copy.deepcopy(suppliers[0]))
        with self.assertRaises(q.ProofRejected):
            q.qualify_case(self.case('formal-supplier-inventory'), [document])
        suppliers.clear()
        q.qualify_case(self.case('formal-empty-inventory'), [document])
        inventory['partitions'][0]['incoming'] = ['unlisted-forwarder']
        with self.assertRaises(q.ProofRejected):
            q.qualify_case(self.case('formal-empty-inventory'), [document])

    def test_negative_controls_require_exact_finite_cause_and_retained_evidence(self):
        document = {'status': 'rejected', 'rejection': {'type': 'COMPLETION_UNPROVEN', 'detail': {
            'model': 'COMPILER_RESOLVED_STATIC_V1', 'cause': {'type': 'CALLBACK_GRAPH_UNPROVEN',
                'graphFailure': {'cause': {'type': 'UNAVAILABLE', 'cause': 'STORED_CALLBACK'}}},
            'evidence': {'type': 'RETAINED'}, 'policyProgress': {'type': 'EVIDENCE_ONLY'}}}}
        runner.negative_control(self.case('mutable-control'), document)
        runner.negative_control(self.case('mutable-capture-control'), document)
        with self.assertRaises(AssertionError):
            runner.negative_control(self.case('external-control'), document)
        for case, cause in (('forwarding-capture-control', 'PARAMETER_ESCAPES'),
                            ('receiver-factory-control', 'UNSUPPORTED_CALLBACK_SUPPLY'),
                            ('getter-control', 'UNSUPPORTED_CALLBACK_SUPPLY'), ('operator-control', 'UNSUPPORTED_CALLBACK_SUPPLY'),
                            ('subjectful-when-control', 'UNSUPPORTED_CALLBACK_SUPPLY')):
            variant = copy.deepcopy(document)
            variant['rejection']['detail']['cause']['graphFailure']['cause']['cause'] = cause
            runner.negative_control(self.case(case), variant)
            with self.assertRaises(AssertionError): runner.negative_control(self.case(case), document)
        unresolved = copy.deepcopy(document)
        unresolved['rejection']['detail']['cause']['graphFailure']['cause'] = {
            'type': 'UNRESOLVED', 'obligations': ['STORED_CALLBACK'], 'scan': 'INCOMPLETE'}
        runner.negative_control(self.case('mutable-capture-control'), unresolved)
        for field, value in (('obligations', []), ('obligations', ['STORED_CALLBACK', 'UNSUPPORTED_CALLBACK_SUPPLY']),
                             ('scan', 'EXHAUSTIVE'), ('type', 'UNAVAILABLE')):
            changed = copy.deepcopy(unresolved)
            changed['rejection']['detail']['cause']['graphFailure']['cause'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(AssertionError):
                runner.negative_control(self.case('mutable-capture-control'), changed)
        document['rejection']['detail']['evidence']['type'] = 'DISCARDED'
        with self.assertRaises(AssertionError):
            runner.negative_control(self.case('mutable-control'), document)

    def test_unused_evidence_qualifies_empty_but_no_evidence_does_not(self):
        use = {'type': 'UNUSED', 'value': value()}
        q.qualify_case(self.case('stored-not-invoked'), [page(immutable(use))])
        with self.assertRaises(q.ProofRejected):
            q.qualify_case(self.case('reference'), [page(immutable(use))])


if __name__ == '__main__':
    unittest.main()
