package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedTraversalOperationsTest {
    @Test
    fun `completed traversal returns to coordination after native confirmation without losing plan identity`() =
        runTest {
            val fixture = RelationPagingFixture.published()
            val plan = plan(fixture)
            val observation = PhaseObservation()
            val requests = mutableListOf<RelationRequest>()
            val relations = RelationOperations { request ->
                requests += request
                observation.phase(IntellijReadPhase.REFERENCE_INVENTORY)
                observation.phase(IntellijReadPhase.REFERENCE_CONFIRMATION)
                complete(request)
            }
            val result =
                assertInstanceOf(
                    TraversalResult.Complete::class.java,
                    hostedTraversalOperations(relations, observation).run(plan),
                )
            assertSame(plan, result.page.plan)
            assertEquals(0, result.page.records.size)
            assertEquals(1, requests.size)
            assertSame(
                fixture.selector,
                (requests.single().subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject)
                    .selector,
            )
            assertEquals(plan.meaning, requests.single().meaning)
            assertEquals(plan.expansion, requests.single().boundary)
            assertEquals(expectedPhases, observation.phases)
        }

    @Test
    fun `incomplete native coverage remains terminal qualified after traversal coordination restores`() = runTest {
        val fixture = RelationPagingFixture.published()
        val plan = plan(fixture)
        val observation = PhaseObservation()
        var reads = 0
        val relations = RelationOperations { request ->
            reads++
            observation.phase(IntellijReadPhase.REFERENCE_INVENTORY)
            observation.phase(IntellijReadPhase.REFERENCE_CONFIRMATION)
            val batch = emptyBatch(request)
            val compilation =
                RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM)).refined()
            RelationReadResult.Qualified(batch, compilation.coverage)
        }
        val result =
            assertInstanceOf(
                TraversalResult.Qualified::class.java,
                hostedTraversalOperations(relations, observation).run(plan),
            )
        assertSame(plan, result.page.plan)
        assertInstanceOf(TraversalQualification.TerminalIncomplete::class.java, result.qualification)
        assertEquals(setOf(TraversalLimitation.ONE_HOP_INCOMPLETE), result.qualification.limitations)
        assertEquals(setOf(RelationLimitation.UNSUPPORTED_ITEM), result.qualification.relationLimitations)
        assertEquals(1, reads)
        assertEquals(expectedPhases, observation.phases)
    }

    @Test
    fun `native rejection retains its finite cause and restores traversal coordination`() = runTest {
        val fixture = RelationPagingFixture.published()
        val observation = PhaseObservation()
        var reads = 0
        val relations = RelationOperations {
            reads++
            observation.phase(IntellijReadPhase.REFERENCE_INVENTORY)
            observation.phase(IntellijReadPhase.REFERENCE_CONFIRMATION)
            RelationReadResult.Rejected(RelationReadRejection.COMPILER_CONTRACT_VIOLATION)
        }
        val result =
            assertInstanceOf(
                TraversalResult.Rejected::class.java,
                hostedTraversalOperations(relations, observation).run(plan(fixture)),
            )
        assertEquals(
            TraversalRejection.OneHopRejected(RelationReadRejection.COMPILER_CONTRACT_VIOLATION),
            result.reason,
        )
        assertEquals(1, reads)
        assertEquals(expectedPhases, observation.phases)
    }

    private fun plan(fixture: RelationPagingFixture): TraversalPlan =
        TraversalPlan.start(
                fixture.selector,
                RelationMeaning.References,
                TraversalBudget(
                    ResultLimit.parse(3).refined(),
                    TraversalByteLimit.parse(100_000).refined(),
                    fixture.budget.resources.workUnitLimit,
                    fixture.budget.resources.elapsedTimeLimit,
                    TraversalDepthLimit.parse(1).refined(),
                    TraversalFrontierLimit.parse(1).refined(),
                    fixture.budget,
                ),
            )
            .refined()

    private fun complete(request: RelationRequest): RelationReadResult.Complete {
        val batch = emptyBatch(request)
        return RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun emptyBatch(request: RelationRequest): RelationBatch =
        RelationBatch.create(
                request,
                emptyList(),
                RelationByteCount.parse(0).refined(),
                RelationWorkCount.parse(0).refined(),
                RelationResultCount.parse(0).refined(),
            )
            .refined()

    private class PhaseObservation : IntellijReadObservation {
        val phases = mutableListOf<IntellijReadPhase>()

        override fun phase(value: IntellijReadPhase) {
            phases += value
        }

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }

    private val expectedPhases =
        listOf(
            IntellijReadPhase.TRAVERSAL,
            IntellijReadPhase.REFERENCE_INVENTORY,
            IntellijReadPhase.REFERENCE_CONFIRMATION,
            IntellijReadPhase.TRAVERSAL,
        )
}
