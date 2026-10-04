package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.RelationScopeExclusionReason
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraversalExpansionDomainTest {
    private val fixture = TraversalTestFixture()
    private val a = fixture.selector("a", 10)
    private val b = fixture.selector("b", 20)
    private val c = fixture.selector("c", 30)

    @Test
    fun `proven domain exit completes within the declared domain and preserves the excluded target`() {
        val expansion = RelationSearchBoundary.Explicit(exactFileScope())
        val plan =
            TraversalPlan.start(a, RelationMeaning.Callees, fixture.plan(a).budget, expansion = expansion).refined()
        var calls = 0
        val operations =
            traversalOperations(
                RelationOperations { request ->
                    calls++
                    excludedRelation(request)
                },
                TraversalNanoClock { 0L },
            )
        val result = assertInstanceOf(TraversalResult.Complete::class.java, runSuspend { operations.run(plan) })
        assertEquals(1, calls)
        assertTrue(result.page.records.isEmpty())
        assertEquals(b.compilerIdentity, result.page.scopeExclusions.single().exclusion.target.compilerIdentity)
        assertEquals(expansion, result.page.scopeExclusions.single().exclusion.requestedDomain)
        assertEquals(0, result.page.scopeExclusions.single().entry.depth.value)
        assertTrue(result.page.partialExpansions.isEmpty())
    }

    @Test
    fun `explicit expansion traverses A B C while retaining a narrower seed scope`() {
        val seed = fixture.selector("a", 10, selectedScope = exactFileScope())
        val expansion = RelationSearchBoundary.Explicit(fixture.scope)
        val requests = mutableListOf<RelationRequest>()
        val relations = RelationOperations { request ->
            requests += request
            assertEquals(expansion, request.boundary)
            assertEquals(fixture.scope, request.searchScope)
            val target =
                when (request.subject.fingerprint.value) {
                    seed.fingerprint.value -> b
                    b.fingerprint.value -> c
                    else -> null
                }
            fixture.completeRelationResult(
                request,
                listOfNotNull(
                    target?.let {
                        fixture.endpoint(request.subject, it, request.searchScope, request.searchConstraints)
                    }
                ),
            )
        }
        val plan =
            TraversalPlan.start(seed, RelationMeaning.Callees, fixture.plan(a).budget, expansion = expansion).refined()
        val result =
            assertInstanceOf(
                TraversalResult.Complete::class.java,
                runSuspend { traversalOperations(relations, TraversalNanoClock { 0L }).run(plan) },
            )
        assertEquals(
            listOf(b.fingerprint.value, c.fingerprint.value),
            result.page.records.map { it.related.fingerprint.value },
        )
        assertEquals(listOf(1, 2), result.page.records.map { it.depth.value })
        assertSame(seed.scope, requests.first().subject.scope)
        assertEquals(3, requests.size)
    }

    @Test
    fun `traversal continuation rejects a changed explicit expansion domain`() {
        val expansion = RelationSearchBoundary.Explicit(fixture.scope)
        val initial = fixture.plan(a, aggregateRecords = 1, oneHop = fixture.relationBudget(records = 1))
        val plan = TraversalPlan.start(a, RelationMeaning.Callees, initial.budget, expansion = expansion).refined()
        val relations = RelationOperations { request ->
            fixture.completeRelationResult(
                request,
                listOf(fixture.endpoint(request.subject, b, request.searchScope, request.searchConstraints)),
            )
        }
        val result =
            assertInstanceOf(
                TraversalResult.Qualified::class.java,
                runSuspend { traversalOperations(relations, TraversalNanoClock { 0L }).run(plan) },
            )
        val continuation = (result.qualification as TraversalQualification.Resumable).continuation
        assertEquals(expansion, continuation.expansion)
        val changed = RelationSearchBoundary.Explicit(exactFileScope())
        assertInstanceOf(
            Refinement.Rejected::class.java,
            TraversalPlan.resume(a, RelationMeaning.Callees, initial.budget, continuation, expansion = changed),
        )
    }

    private fun exactFileScope() =
        SymbolSearchScope.ExactFile(
            (a.file as SymbolDiscoveryFileIdentity.Workspace).path,
            a.scope.sourceKinds,
            a.scope.generatedSources,
        )

    private fun excludedRelation(request: RelationRequest): RelationReadResult.Complete {
        val evidence = fixture.endpoint(request.subject, b).evidence
        val exclusion =
            RelationScopeExclusion.fromNativeBoundary(
                    request,
                    RelationOccurrence.fromBoundary(request.subject.file, 10, 11).refined(),
                    evidence,
                    RelationScopeExclusionReason.SOURCE_DOMAIN,
                )
                .refined()
        val bytes = exclusion.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong()
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(bytes).refined(),
                    RelationWorkCount.parse(1L).refined(),
                    RelationResultCount.parse(0).refined(),
                    scopeExclusions = listOf(exclusion),
                )
                .refined()
        val complete = RelationCompilation.complete(batch)
        return RelationReadResult.Complete(batch, complete.coverage)
    }

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
