"""Synthetic receipts prove the verifier rejects missing authority and weak equality."""
import copy
import unittest
import immutable_reuse_qualification as q


def page(host='first', epoch=1):
    live = {'root': '/owned', 'host': host, 'epoch': epoch, 'contentView': 'SAVED_PSI_COMMITTED', 'version': 1}
    basis = {'type': 'LIVE', **{key: value for key, value in live.items() if key != 'version'}, 'referenceVersion': 1}
    return {'status': 'complete', 'live': live, 'coverage': {'exhaustive': True}, 'items': [], 'failures': [], 'omissions': [],
            'relation_observations': [{'target': 'alpha', 'value': {'basis': basis, 'file': '/owned/F.kt',
                'range': {'startInclusive': 10, 'endExclusive': 20}, 'candidateSelector': host + '-handle',
                'transfers': [{'kind': 'ARGUMENT', 'source': 10, 'target': 20}], 'factories': [{'capture': 'alpha'}]}}]}


class ImmutableReuseQualificationTest(unittest.TestCase):
    def test_fresh_host_authority_can_differ_only_after_each_nested_basis_is_current(self):
        q.assert_equal_answers([page()], [page('second', 8)])
        stale = page('second', 8)
        stale['relation_observations'][0]['value']['basis']['epoch'] = 1
        with self.assertRaises(q.ReuseRejected) as error: q.assert_equal_answers([page()], [stale])
        self.assertEqual(q.ReuseFailure.STALE_EVIDENCE, error.exception.cause)

    def test_same_targets_do_not_hide_wrong_capture_transfer_site_or_qualification(self):
        for field, replacement in (('factories', []), ('transfers', []), ('range', {'startInclusive': 11, 'endExclusive': 20}), ('file', '/foreign')):
            changed = page('second', 2)
            changed['relation_observations'][0]['value'][field] = replacement
            with self.subTest(field=field), self.assertRaises(q.ReuseRejected): q.assert_equal_answers([page()], [changed])
        changed = page();changed['omissions'] = ['qualified']
        with self.assertRaises(q.ReuseRejected): q.assert_equal_answers([page()], [changed])

    def test_missing_or_partial_answers_and_unqualified_handles_fail_closed(self):
        for mutation in ('partial', 'missing', 'handle'):
            changed = page()
            if mutation == 'partial': changed['coverage']['exhaustive'] = False
            if mutation == 'missing': changed['relation_observations'] = []
            if mutation == 'handle': del changed['relation_observations'][0]['value']['file']
            with self.subTest(mutation=mutation), self.assertRaises(q.ReuseRejected): q.assert_equal_answers([page()], [changed])

    def test_subject_tokens_require_exact_query_location_and_compiler_endpoint(self):
        first = page()
        first['question'] = {'from': {'type': 'location', 'file': 'F.kt', 'offset': 12},
                             'steps': [{'type': 'related', 'relation': 'callees'}]}
        first['relation_observations'][0]['subject'] = {'token': 'first'}
        first['items'] = [{'relation': {'meaning': 'callees', 'source': {'file': '/owned/F.kt',
            'range': {'startInclusive': 10, 'endExclusive': 20}, 'compilerEvidence': {'signature': {'name': 'seed'}}}}}]
        second = copy.deepcopy(first)
        second['relation_observations'][0]['subject']['token'] = 'second'
        q.assert_equal_answers([first], [second])
        second['items'][0]['relation']['source']['range']['endExclusive'] = 11
        with self.assertRaises(q.ReuseRejected) as error: q.assert_equal_answers([first], [second])
        self.assertEqual(q.ReuseFailure.UNPROVEN_HANDLE, error.exception.cause)
        second = copy.deepcopy(first); second['question']['from']['offset'] = 13
        with self.assertRaises(q.ReuseRejected): q.assert_equal_answers([first], [second])

    def test_unrelated_partition_requires_actual_reuse_and_no_extraction_or_invalidation(self):
        counters = {'SEMANTIC_FACT_PARTITIONS_REUSED': 1, 'SEMANTIC_FACT_PARTITIONS_EXTRACTED': 0,
                    'SEMANTIC_FACT_PARTITIONS_INVALIDATED': 0, 'SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS': 1,
                    'SEMANTIC_FACT_DEPENDENCY_REJECTIONS': 0}
        q.assert_partition_activity(counters, reused=True)
        for name in counters:
            changed = copy.deepcopy(counters)
            changed[name] = 0 if changed[name] else 1
            with self.subTest(name=name), self.assertRaises(q.ReuseRejected): q.assert_partition_activity(changed, reused=True)
        del counters['SEMANTIC_FACT_PARTITIONS_REUSED']
        with self.assertRaises(q.ReuseRejected): q.assert_partition_activity(counters, reused=True)


if __name__ == '__main__': unittest.main()
