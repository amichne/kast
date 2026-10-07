"""Fixture integrity checks; these do not establish native K2 behavior."""

from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from static_callback_oracle import (
    CallableValueRejection,
    CompleteCase,
    FixtureIntegrityError,
    FlowObligation,
    SOURCE_FILES,
    SourceDocument,
    load_oracle,
    materialize,
)


FIXTURE = Path(__file__).parent / 'static-callback-fixture'


class StaticCallbackOracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = tuple(SourceDocument(relative, (FIXTURE / relative).read_text(encoding='utf-8'))
                            for relative in SOURCE_FILES)

    def oracle(self, sources=None):
        return materialize(str(FIXTURE.resolve()), self.sources if sources is None else sources)

    def test_recursive_graphs_preserve_cycle_edges_and_simple_invocation_routes(self):
        cases = {case.name: case for case in self.oracle().complete_cases}
        closed = cases['recursive']
        self.assertEqual(['recursiveWrapper'], [formal.callable.name for formal in closed.graph.formals])
        self.assertEqual([('recursiveWrapper', 'recursiveWrapper')],
                         [(edge.source.callable.name, edge.target.callable.name) for edge in closed.graph.forwardings])
        self.assertFalse(hasattr(closed, 'invocation'))
        self_recursive = cases['self-recursive-invocation']
        self.assertEqual(2, len(self_recursive.supplies))
        self.assertEqual('selfInvokeWrapper', self_recursive.invocation.owner.name)
        self.assertEqual((), self_recursive.forwardings)
        self.assertEqual(1, len(self_recursive.graph.forwardings))
        self.assertNotEqual(self_recursive.supplies[0].body, self_recursive.supplies[1].body)
        mutual = cases['mutual-recursive-invocation']
        self.assertEqual(['mutualFirst', 'mutualSecond', 'invokeCallback'],
                         [formal.callable.name for formal in mutual.graph.formals])
        self.assertEqual({('mutualFirst', 'mutualSecond'), ('mutualSecond', 'mutualFirst'),
                          ('mutualSecond', 'invokeCallback')},
                         {(edge.source.callable.name, edge.target.callable.name) for edge in mutual.graph.forwardings})
        self.assertEqual(['mutualSecond', 'invokeCallback'],
                         [edge.target.callable.name for edge in mutual.forwardings])
        self.assertEqual('invokeCallback', mutual.invocation.owner.name)

    def test_two_suppliers_keep_distinct_bodies_while_sharing_cross_module_formals(self):
        alpha, beta = self.oracle().complete_cases[:2]
        self.assertEqual('alphaEntry', alpha.supply.supplier.name)
        self.assertEqual('betaEntry', beta.supply.supplier.name)
        self.assertEqual('{ alphaSink() }', alpha.supplies[0].body.text)
        self.assertEqual('{ alphaSink(); "alpha" }', alpha.supplies[1].body.text)
        self.assertEqual('{ betaSink() }', beta.supply.body.text)
        self.assertEqual(2, len(alpha.supplies))
        self.assertEqual(1, len(beta.supplies))
        self.assertEqual([1, 1], [len(supply.target_occurrences) for supply in alpha.supplies])
        self.assertEqual(1, len(beta.supply.target_occurrences))
        self.assertNotEqual(alpha.supplies[0].target_occurrences, alpha.supplies[1].target_occurrences)
        self.assertNotEqual(alpha.supplies[0].body, alpha.supplies[1].body)
        self.assertNotEqual(alpha.supplies[0].call, alpha.supplies[1].call)
        self.assertEqual(alpha.supplies[0].supplier, alpha.supplies[1].supplier)
        self.assertEqual(alpha.supplies[0].formal, alpha.supplies[1].formal)
        self.assertNotEqual(alpha.supply.body.range, beta.supply.body.range)
        self.assertEqual(alpha.supply.formal, beta.supply.formal)
        self.assertEqual(alpha.forwardings, beta.forwardings)
        self.assertEqual(['forwardOnce', 'invokeCallback'],
                         [step.target.callable.name for step in alpha.forwardings])
        self.assertEqual('block()', alpha.invocation.occurrence.text)
        self.assertEqual('invokeCallback', alpha.invocation.owner.name)
        self.assertEqual('fixture.staticcallbacks.invocation.alphaSink', alpha.supply.target.fqn)
        self.assertIn(beta.supply.target, alpha.forbidden_targets)
        self.assertIn(alpha.supply.target, beta.forbidden_targets)
        module_names = {Path(symbol.declaration.file).relative_to(FIXTURE.resolve()).parts[0]
                        for symbol in self.oracle().symbols}
        self.assertEqual({'suppliers', 'forwarding', 'invocation'}, module_names)

    def test_exact_spans_retain_authored_source(self):
        suite = load_oracle(FIXTURE)
        spans = []
        for symbol in suite.symbols:
            spans.extend((symbol.declaration, symbol.name_site))
        for case in suite.complete_cases:
            for supply in case.supplies:
                spans.extend(supply.target_occurrences)
                spans.extend((supply.body, supply.call, supply.formal.parameter))
            if isinstance(case, CompleteCase):
                spans.append(case.invocation.occurrence)
            for formal in case.graph.formals:
                spans.append(formal.parameter)
            for step in case.graph.forwardings:
                spans.extend((step.source.parameter, step.target.parameter, step.argument, step.call))
        for span in spans:
            encoded = Path(span.file).read_text(encoding='utf-8').encode('utf-16-le')
            actual = encoded[span.range.startInclusive * 2:span.range.endExclusive * 2].decode('utf-16-le')
            self.assertEqual(span.text, actual)

    def test_offsets_are_utf16_when_non_bmp_text_precedes_declarations(self):
        baseline = self.oracle().complete_cases[0].supply.supplier
        # One extra source line occupies six UTF-16 units and five Python characters.
        changed = replace(self.sources[0], text=self.sources[0].text.replace(
            'package fixture.staticcallbacks.suppliers\n',
            'package fixture.staticcallbacks.suppliers\n// 🧭\n', 1))
        shifted = self.oracle((changed,) + self.sources[1:]).complete_cases[0].supply.supplier
        self.assertEqual(6, shifted.name_site.range.startInclusive - baseline.name_site.range.startInclusive)
        self.assertEqual(6, shifted.declaration.range.endExclusive - baseline.declaration.range.endExclusive)

    def test_attached_leading_comments_belong_to_authored_psi_declaration_envelopes(self):
        by_name = {symbol.name: symbol for symbol in self.oracle().symbols}
        # Independently authored source boundaries. Kast projects PSI textRange;
        # an attached comment is part of the declaration, but not its name token.
        expected = (
            ('alphaEntry', 727, 920, 812,
             '// The suppliers share the formal route, but each owns a distinct callback body.\n'),
            ('callableReferenceEntry', 977, 1127, 1062,
             '// Callable references remain outside the admitted COMPLETE_ONLY callback model.\n'),
            ('recursiveEntry', 1129, 1274, 1211,
             '// Static recursive graphs: never execute these functions to qualify a query.\n'),
        )
        for name, start, end, name_start, prefix in expected:
            symbol = by_name[name]
            self.assertEqual((start, end), (symbol.declaration.range.startInclusive,
                                          symbol.declaration.range.endExclusive))
            self.assertEqual(name_start, symbol.name_site.range.startInclusive)
            self.assertTrue(symbol.declaration.text.startswith(prefix + 'fun ' + name + '('))
        self.assertTrue(by_name['betaEntry'].declaration.text.startswith('fun betaEntry('))
        self.assertTrue(by_name['externalEscapeEntry'].declaration.text.startswith('fun externalEscapeEntry('))

    def test_negative_controls_name_current_closed_model_boundaries(self):
        suite = self.oracle()
        external, formal = suite.rejected_cases
        reference, = suite.excluded_cases
        self.assertEqual((FlowObligation.EXTERNAL_CALLABLE, FlowObligation.NO_INVOCATION_PROVEN),
                         external.rejection.obligations)
        self.assertEqual('java.util.Collections.singletonList(block)', external.cause_site.text)
        self.assertEqual(CallableValueRejection(), formal.rejection)
        self.assertEqual('invokeCallback', formal.owner.name)
        self.assertEqual('block()', formal.invocation.occurrence.text)
        self.assertEqual(formal.owner, formal.formal.callable)
        self.assertFalse(hasattr(reference, 'rejection'))
        self.assertEqual('::referenceSink', reference.reference.text)
        self.assertEqual('referenceSink', reference.target_occurrence.text)
        self.assertEqual(2, reference.target_occurrence.range.startInclusive - reference.reference.range.startInclusive)

    def test_one_result_resource_control_preserves_same_alpha_supply_and_canonical_failure(self):
        suite = self.oracle()
        resource, = suite.resource_cases
        self.assertEqual('alpha-result-capacity', resource.name)
        self.assertEqual(1, resource.max_results)
        self.assertEqual(suite.complete_cases[0].supply, resource.supply)
        self.assertEqual(suite.complete_cases[0].supplies, resource.supplies)
        self.assertEqual(2, len(resource.supplies))
        self.assertEqual(2, sum(len(supply.target_occurrences) for supply in resource.supplies))
        self.assertEqual((FlowObligation.NO_INVOCATION_PROVEN, FlowObligation.RESULT_LIMIT_REACHED),
                         resource.rejection.obligations)
        self.assertEqual('INCOMPLETE', resource.rejection.scan)
        self.assertEqual('COMPLETE', resource.original_coverage.type)
        self.assertEqual('invokeCallback(block)', resource.cause_site.text)
        self.assertEqual(suite.complete_cases[0].forwardings[1].call, resource.cause_site)
        self.assertNotIn(resource, suite.cases)

    def test_case_supplies_require_one_exact_named_root_and_nonempty_inventory(self):
        alpha, beta = self.oracle().complete_cases[:2]
        with self.assertRaises(FixtureIntegrityError):
            replace(alpha, supplies=())
        with self.assertRaises(FixtureIntegrityError):
            replace(alpha, supplies=(alpha.supply, beta.supply))
        recursive = self.oracle().complete_cases[2]
        with self.assertRaises(FixtureIntegrityError):
            replace(alpha, graph=recursive.graph)

    def test_graph_inventory_rejects_missing_unreachable_and_duplicate_formals_or_edges(self):
        alpha, beta, recursive, self_recursive, mutual = self.oracle().complete_cases
        for changed in (
            {'formals': mutual.graph.formals[:2]},
            {'formals': mutual.graph.formals + (recursive.graph.root,)},
            {'formals': mutual.graph.formals + (mutual.graph.root,)},
            {'forwardings': mutual.graph.forwardings + (mutual.graph.forwardings[0],)},
        ):
            with self.subTest(changed=changed), self.assertRaises(FixtureIntegrityError):
                replace(mutual.graph, **changed)

    def test_mutated_route_and_duplicate_declaration_reject_fixture(self):
        route_change = replace(self.sources[1], text=self.sources[1].text.replace(
            '= forwardOnce(block)', '= invokeCallback(block)'))
        with self.assertRaises(FixtureIntegrityError):
            self.oracle((self.sources[0], route_change, self.sources[2]))
        duplicate = replace(self.sources[0], text=self.sources[0].text +
                            '\nfun alphaEntry(): String = sharedWrapper { alphaSink(); alphaSink() }\n')
        with self.assertRaises(FixtureIntegrityError):
            self.oracle((duplicate,) + self.sources[1:])

    def test_missing_source_and_nonabsolute_root_reject_fixture(self):
        with self.assertRaises(FixtureIntegrityError):
            self.oracle(self.sources[:2])
        with self.assertRaises(FixtureIntegrityError):
            materialize('relative', self.sources)

    def test_additional_kotlin_source_rejects_fixture_at_load_boundary(self):
        with TemporaryDirectory(prefix='kast-static-callback-oracle-') as temporary:
            root = Path(temporary)
            for source in self.sources:
                destination = root / source.relative_path
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(source.text, encoding='utf-8')
            load_oracle(root)
            unexpected = root / 'suppliers/src/main/kotlin/Unexpected.kt'
            unexpected.write_text('fun unexpected() = Unit\n', encoding='utf-8')
            with self.assertRaises(FixtureIntegrityError):
                load_oracle(root)


if __name__ == '__main__':
    unittest.main()
