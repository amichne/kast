package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.traversal.contract.TraversalCallableObservation
import io.github.amichne.kast.traversal.contract.TraversalCallableObservationFailure
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraversalCallableObservationTest {
    private val fixture = TraversalTestFixture()
    private val owner = fixture.selector("owner", 10)

    @Test
    fun `known source-less boundary survives traversal with original frontier and no invented edge`() {
        val plan = fixture.plan(owner)
        val operations =
            traversalOperations(RelationOperations { request -> result(request) }, TraversalNanoClock { 0L })
        val complete = assertInstanceOf(TraversalResult.Complete::class.java, runSuspend { operations.run(plan) })
        assertTrue(complete.page.records.isEmpty())
        val observed = complete.page.callableObservations.single()
        assertEquals(owner.fingerprint.value, observed.entry.node.fingerprint.value)
        assertEquals(0, observed.entry.depth.value)
        assertEquals(
            SourceLessCallableDisposition.BUILTIN_BOUNDARY,
            (observed.observation.target as RelationCallableTarget.SourceLess).disposition,
        )
        assertEquals(
            observed.observation.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong(),
            complete.page.encodedBytes.value,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (complete.page.callableObservations as MutableList<TraversalCallableObservation>).clear()
        }
    }

    @Test
    fun `callable frontier proof rejects a changed expansion domain`() {
        val plan = fixture.plan(owner)
        val request = RelationRequest.start(owner, RelationMeaning.Callees, fixture.relationBudget())
        val observation = result(request).batch.callableObservations.single()
        val expanded =
            TraversalPlan.start(
                    owner,
                    RelationMeaning.Callees,
                    plan.budget,
                    expansion = RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
                .refined()
        val entry = TraversalFrontierEntry.create(expanded, TraversalNode.start(owner), TraversalDepth.Zero).refined()
        assertEquals(
            Refinement.Rejected(TraversalCallableObservationFailure.DOMAIN_MISMATCH),
            TraversalCallableObservation.create(expanded, entry, observation),
        )
    }

    private fun result(request: RelationRequest): RelationReadResult.Complete {
        val evidence = CompilerGroundedSymbolEvidence.fromSelector(owner)
        val sourceLess =
            SourceLessCallable.fromCompiler(
                    CanonicalCompilerSignature.function("kotlin.Function0.invoke", null, emptyList(), emptyList(), 0)
                        .refined(),
                    CompilerSymbolKind.FUNCTION,
                    SourceLessCallableOrigin.LIBRARY,
                    SourceLessCallableModuleKind.BUILTINS,
                    SourceLessCallableModuleName.parse("Built-ins").refined(),
                )
                .refined()
        val observation =
            RelationCallableObservation.fromNativeBoundary(
                    request,
                    RelationOccurrence.fromBoundary(owner.file, 12, 13).refined(),
                    evidence,
                    RelationCallableBody.Named.fromCompiler(evidence).refined(),
                    RelationCallableTarget.SourceLess(sourceLess, SourceLessCallableDisposition.BUILTIN_BOUNDARY),
                )
                .refined()
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(observation.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(0).refined(),
                    callableObservations = listOf(observation),
                )
                .refined()
        return RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
