package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImpactRequestedSiteAdmissionTest {
    private val projectionReferences = CanonicalQueryReferences()

    @Test
    fun `native anonymous owner must match the supplied requested site owner`() = runTest {
        for (matching in listOf(true, false)) {
            val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
            val range = f.range(10, 100)
            val signature =
                CanonicalCompilerSignature.function(
                        RelationCallableBody.Anonymous.sourceIdentity(f.owner.file, range),
                        null,
                        emptyList(),
                        emptyList(),
                        0,
                    )
                    .value() as CanonicalCompilerSignature.Function
            val body = RelationCallableBody.Anonymous.fromCompiler(f.owner.file, range, signature).value()
            val owner = ValueExecutionOwner.fromCompiler(f.owner, body, f.owner.lease.identity).value()
            val actual = ValueSite.fromCompiler(f.owner, f.range(20, 35), ValueRole.ExpressionResult, owner).value()
            val selected =
                if (matching) actual.impactDocument(references = projectionReferences).value()
                else f.position(20, 35).site
            val request = f.document().copy(models = bounded(emptyList()), requestedSites = bounded(listOf(selected)))
            val original = f.native()
            val native =
                object : ValueProducerSeedCompilerPort by original {
                    override suspend fun revalidateSite(request: ValueSiteRevalidationRequest): ValueModelSiteRead {
                        val read = original.revalidateSite(request) as ValueModelSiteRead.Revalidated
                        return read.copy(position = RevalidatedValueSite.fromCompiler(request, actual).value())
                    }
                }
            val outcome = request.admitImpact(f.owner.lease, f.references, native, f.budget)
            if (matching) {
                assertEquals(owner, outcome.value().source.requestedSites.single().site.executionOwner)
            } else {
                assertEquals(QueryImpactSourceFailureCode.OWNER_MISMATCH, outcome.failureCode())
            }
            assertEquals(listOf(f.range(20, 35)), original.positions)
        }
    }

    @Test
    fun `requested sites reuse exact model site native receipts once and preserve original question order`() = runTest {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val targets = listOf(f.position(60, 75).site, f.position(20, 35).site)
        val request = f.document().copy(requestedSites = bounded(targets))
        val native = f.native()
        val admitted = request.admitImpact(f.owner.lease, f.references, native, f.budget).value()
        assertEquals(listOf(10L, 7L, 6L, 4L), native.grants)
        assertEquals(8L, admitted.examinedWork)
        assertEquals(
            targets.map { it.range.start.value },
            admitted.source.requestedSites.map { it.site.range.startInclusive },
        )
        assertEquals(listOf(2L, 2L), admitted.source.requestedSites.map { it.examinedWorkUnits.value })
        assertEquals(
            listOf(4L, 6L),
            admitted.source.requestedSites.map { it.request.budget.resources.workUnitLimit.value },
        )
        native.assertConsumed()
    }

    @Test
    fun `native requested site failures preserve finite cause and abort without following target reads`() = runTest {
        val cases =
            listOf(
                ValueFlowRejection.STALE_SITE to QueryImpactSourceFailureCode.STALE_REQUESTED_SITE,
                ValueFlowRejection.UNSUPPORTED_SEED to QueryImpactSourceFailureCode.UNSUPPORTED_REQUESTED_SITE,
                ValueFlowRejection.UNRESOLVED_SEED to QueryImpactSourceFailureCode.UNRESOLVED_REQUESTED_SITE,
            )
        for ((nativeCause, expected) in cases) {
            val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
            val native = f.native(nativeCause)
            val request =
                f.document()
                    .copy(
                        models = bounded(emptyList()),
                        requestedSites = bounded(listOf(f.position(20, 35).site, f.position(60, 75).site)),
                    )
            val outcome = request.admitImpact(f.owner.lease, f.references, native, f.budget)
            assertEquals(expected, outcome.failureCode())
            assertEquals(listOf(10L, 7L, 6L), native.grants)
            assertEquals(listOf(f.range(20, 35)), native.positions)
        }
    }

    @Test
    fun `requested native proof remains charged within explicit small checkpoint grant`() = runTest {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val request =
            f.document()
                .copy(
                    models = bounded(emptyList()),
                    requestedSites = bounded(listOf(f.position(20, 35).site, f.position(60, 75).site)),
                )
        val native = f.native()
        val budget = f.budget.copy(checkpointBytes = QueryByteLimit.parse(524288).value())
        val outcome = request.admitImpact(f.owner.lease, f.references, native, budget)
        org.junit.jupiter.api.Assertions.assertTrue(
            outcome is Refinement.Refined,
            "$outcome byteGrants=${native.byteGrants} siteCharges=${native.siteCharges()}",
        )
        val admitted = outcome.value()
        org.junit.jupiter.api.Assertions.assertTrue(admitted.source.retainedBytes <= 524288)
        org.junit.jupiter.api.Assertions.assertTrue(admitted.source.retainedBytes > 0)
        assertEquals(2, admitted.source.requestedSites.size)
        native.assertConsumed()
        val limited = f.native()
        assertEquals(
            QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED,
            request
                .admitImpact(
                    f.owner.lease,
                    f.references,
                    limited,
                    budget.copy(checkpointBytes = QueryByteLimit.parse(1).value()),
                )
                .failureCode(),
        )
        assertEquals(listOf(10L), limited.grants)
    }

    private fun QueryImpactBoundarySourceAdmissionTest.Fixture.Native.siteCharges(): String {
        val graph = QueryImpactRetainedGraph()
        val shared = sites.map(graph::site)
        val separate = sites.map { QueryImpactRetainedGraph().site(it) }
        val sameOwner = sites.drop(1).all { it.enclosing === sites.first().enclosing }
        return "shared=$shared separate=$separate sameOwner=$sameOwner"
    }

    @Test
    fun `duplicate requested sites reject before all native effects`() = runTest {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val site = f.position(60, 75).site
        val native = f.native()
        val outcome =
            f.document()
                .copy(requestedSites = bounded(listOf(site, site)))
                .admitImpact(f.owner.lease, f.references, native, f.budget)
        assertEquals(QueryImpactSourceFailureCode.DUPLICATE_REQUESTED_SITE, outcome.failureCode())
        assertEquals(emptyList<Long>(), native.grants)
    }

    @Test
    fun `foreign requested native claim rejects before all native effects`() = runTest {
        val f = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val site = f.position(60, 75).site
        val foreign =
            site.copy(
                enclosing =
                    site.enclosing.copy(
                        basis =
                            ImpactSemanticBasisDocument.Published(
                                f.text("/other"),
                                ImpactEvidenceRevisionDocument.parse(7).value(),
                            )
                    )
            )
        val native = f.native()
        val outcome =
            f.document()
                .copy(requestedSites = bounded(listOf(foreign)))
                .admitImpact(f.owner.lease, f.references, native, f.budget)
        assertEquals(QueryImpactSourceFailureCode.BASIS_MISMATCH, outcome.failureCode())
        assertEquals(emptyList<Long>(), native.grants)
    }
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value

private fun Refinement<*, QueryRunRejection>.failureCode(): QueryImpactSourceFailureCode =
    (((this as Refinement.Rejected).failure as QueryRunRejection.ImpactSourceRejected).cause
            as QueryImpactSourceFailureDocument.Admission)
        .cause
