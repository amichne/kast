package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proves admission and bounded outcome instrumentation. No native PSI/K2 success is simulated. */
class ValueFlowAuthorityBoundaryTest {
    private val request = RelationReadTest().request(RelationMeaning.References)
    private val selector = (request.subject as RelationEndpoint.Subject).selector
    private val anchor =
        ExactDeclarationTextRange.parse(selector.range.startInclusive + 1, selector.range.startInclusive + 2).refined()
    private val current = SemanticReadLease(selector.lease.workspaceRoot, EvidenceGeneration.parse(99).refined())
    private val rejectedModel =
        WorkspaceSearchScopeModel.compile(
            selector.lease.workspaceRoot,
            ImportedWorkspaceModelState.INCOMPLETE,
            emptyList(),
        )
    private val project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            error("Moved authority must not call native project method ${method.name}")
        } as Project

    @Test
    fun `moved authority stops before native reads and retains zero work`() = runTest {
        val observation = Observation()
        val site = ValueSite.fromCompiler(request.subject, anchor, ValueRole.ExpressionResult).refined()
        val result =
            IntellijValueFlowCompilerAdapter(observation)
                .read(
                    project,
                    current,
                    ValueFlowRequest(site, request.budget, request.boundary),
                    rejectedModel,
                )
        val rejected = assertInstanceOf(ValueFlowRead.Rejected::class.java, result)
        assertEquals(ValueFlowRejection.AUTHORITY_MOVED, rejected.cause)
        assertEquals(0L, rejected.examinedWorkUnits.value)
        assertEquals(listOf(IntellijReadCounter.VALUE_FLOW_REJECTIONS), observation.counts)
    }

    @Test
    fun `moved producer authority reports rejection without native confirmation`() = runTest {
        val observation = Observation()
        val source =
            ValueProducerSeedRequest.create(selector, anchor, selector, request.budget, request.boundary).refined()
        val result = IntellijValueFlowCompilerAdapter(observation).seed(project, current, source, rejectedModel)
        assertEquals(
            ValueProducerSeedRejection.AUTHORITY_MOVED,
            assertInstanceOf(ValueProducerSeedRead.Rejected::class.java, result).cause,
        )
        assertEquals(
            listOf(
                IntellijReadCounter.VALUE_PRODUCER_SEED_READS,
                IntellijReadCounter.VALUE_PRODUCER_SEED_REJECTIONS,
            ),
            observation.counts,
        )
        assertFalse(IntellijReadCounter.VALUE_PRODUCER_SEEDS_CONFIRMED in observation.counts)
    }

    @Test
    fun `moved boundary authority stops before native role validation`() = runTest {
        val observation = Observation()
        val source =
            ValueSiteRevalidationRequest.create(
                    selector,
                    anchor,
                    ValueSiteRoleClaim.ExpressionResult,
                    request.budget,
                )
                .refined()
        val result =
            IntellijValueFlowCompilerAdapter(observation).revalidateSite(project, current, source, rejectedModel)
        assertEquals(
            ValueFlowRejection.AUTHORITY_MOVED,
            assertInstanceOf(ValueModelSiteRead.Rejected::class.java, result).cause,
        )
        assertEquals(
            listOf(
                IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS,
                IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED,
            ),
            observation.counts,
        )
        assertTrue(observation.failures.isEmpty())
    }

    @Test
    fun `unexpected project inspection retains typed stage without exception payload`() = runTest {
        val observation = Observation()
        val faulty =
            Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, _, _ ->
                throw IllegalStateException("SOURCE_PAYLOAD_MUST_NOT_BE_RECORDED")
            } as Project
        val source =
            ValueSiteRevalidationRequest.create(
                    selector,
                    anchor,
                    ValueSiteRoleClaim.ExpressionResult,
                    request.budget,
                )
                .refined()
        val result =
            IntellijValueFlowCompilerAdapter(observation).revalidateSite(faulty, selector.lease, source, rejectedModel)
        assertEquals(
            ValueFlowRejection.NATIVE_UNAVAILABLE,
            assertInstanceOf(ValueModelSiteRead.Rejected::class.java, result).cause,
        )
        val failure = observation.failures.single()
        assertEquals(IntellijReadStage.VALUE_MODEL_SITE_ADMISSION, failure.stage)
        assertFalse(failure.toString().contains("SOURCE_PAYLOAD_MUST_NOT_BE_RECORDED"))
        assertEquals(
            listOf(
                IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS,
                IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED,
            ),
            observation.counts,
        )
    }

    private class Observation : IntellijReadObservation {
        val counts = mutableListOf<IntellijReadCounter>()
        val failures = mutableListOf<IntellijReadUnexpectedFailure>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(1, amount)
            counts += counter
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
            error("Moved authority is not a native termination")

        override fun unexpected(failure: IntellijReadUnexpectedFailure) {
            failures += failure
        }
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
