package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Selection proves only the exact routing role; the token and site still require the peer's native authority. */
class QueryImpactPeerSelectionTest {
    @Test
    fun `only exact foreign continuation target declarations leave source admission`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val source = peerSource(f)
        val model = source.models.values.single() as ImpactModelDocument.Boundary
        val target = (model.rules.values.single() as ImpactBoundaryRuleDocument.Continuation).target
        val peer = source.declarations.values.last()
        val result = source.selectPeerSites(f.owner.lease)
        assertTrue(
            result is Refinement.Refined,
            "the reviewed target role should select an independent peer read: $result",
        )
        val selected = (result as Refinement.Refined).value.single()
        assertEquals(target.site, selected.site)
        assertEquals(listOf(peer), selected.declarations)
        assertEquals(0, selected.modelPosition.value)
        assertEquals(0, selected.rulePosition.value)
    }

    @Test
    fun `all local boundary rules need no peer effect`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        assertEquals(emptyList<QueryImpactPeerSelection>(), f.document().selectPeerSites(f.owner.lease).refined())
    }

    @Test
    fun `same root changed epoch cannot masquerade as an independent peer`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        assertRejected(peerSource(f, "/workspace"), f, QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH)
    }

    @Test
    fun `unrelated foreign declaration is excluded before any native acquisition`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val source = peerSource(f)
        val unrelated =
            source.declarations.values.last().let {
                it.copy(declaration = it.declaration.copy(file = f.text("/client/Unrelated.kt")))
            }
        assertRejected(
            source.copy(declarations = bounded(source.declarations.values + unrelated)),
            f,
            QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
        )
    }

    @Test
    fun `peer target requires its exact declaration inventory`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val source = peerSource(f)
        assertRejected(
            source.copy(declarations = bounded(source.declarations.values.dropLast(1))),
            f,
            QueryImpactSourceFailureCode.MISSING_DECLARATION,
        )
    }

    @Test
    fun `peer token cannot also serve a producer or source declaration`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val source = peerSource(f)
        val peerToken = source.declarations.values.last().reference
        assertRejected(
            source.copy(seeds = bounded(source.seeds.values.map { it.copy(enclosing = peerToken) })),
            f,
            QueryImpactSourceFailureCode.BASIS_MISMATCH,
        )
        assertRejected(
            source.copy(
                declarations =
                    bounded(
                        source.declarations.values.mapIndexed { index, value ->
                            if (index == 0) value.copy(reference = peerToken) else value
                        }
                    )
            ),
            f,
            QueryImpactSourceFailureCode.BASIS_MISMATCH,
        )
    }

    @Test
    fun `foreign source and independently requested sites remain forbidden`() {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val source = peerSource(f)
        val model = source.models.values.single() as ImpactModelDocument.Boundary
        val rule = model.rules.values.single() as ImpactBoundaryRuleDocument.Continuation
        assertRejected(
            source.copy(models = bounded(listOf(model.copy(rules = bounded(listOf(rule.copy(source = rule.target))))))),
            f,
            QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
        )
        assertRejected(
            source.copy(requestedSites = bounded(listOf(rule.target.site))),
            f,
            QueryImpactSourceFailureCode.BASIS_MISMATCH,
        )
    }

    private fun assertRejected(
        source: QueryImpactSourceDocument,
        f: QueryImpactBoundarySourceAdmissionTest.Fixture,
        expected: QueryImpactSourceFailureCode,
    ) {
        val failure = (source.selectPeerSites(f.owner.lease) as Refinement.Rejected).failure
        assertEquals(
            expected,
            ((failure as QueryRunRejection.ImpactSourceRejected).cause as QueryImpactSourceFailureDocument.Admission)
                .cause,
        )
    }

    private fun peerSource(
        f: QueryImpactBoundarySourceAdmissionTest.Fixture,
        root: String = "/client",
    ): QueryImpactSourceDocument {
        val original = f.document()
        val model = original.models.values.single() as ImpactModelDocument.Boundary
        val rule = model.rules.values.first() as ImpactBoundaryRuleDocument.Continuation
        val target =
            rule.target.copy(
                site =
                    rule.target.site.copy(
                        enclosing =
                            rule.target.site.enclosing.copy(
                                basis =
                                    ImpactSemanticBasisDocument.Live(
                                        f.text(root),
                                        f.text("12345678-1234-1234-1234-123456789abc"),
                                        ImpactEvidenceRevisionDocument.parse(9).refined(),
                                        ImpactContentViewDocument.SAVED_PSI_COMMITTED,
                                        ImpactModelFormatDocument.Current,
                                    ),
                                file = f.text("$root/File.kt"),
                            )
                    )
            )
        val peer = QueryImpactDeclarationDocument(f.text("unproven-peer-token"), target.site.enclosing)
        return original.copy(
            models = bounded(listOf(model.copy(rules = bounded(listOf(rule.copy(target = target)))))),
            declarations = bounded(original.declarations.values + peer),
        )
    }

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
