package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Matched production reader with its original disabled owner and admitted one-slot owner; native effects are scripted.
 */
class ReferenceInventoryWorkTest {
    @Test
    fun `reuse removes preparation effects while preserving full confirmation and negative controls`() {
        for (path in WorkPath.entries) for (limit in listOf(1, 5, 20)) {
            val baseline = measure(path, OwnerMode.DISABLED, limit)
            val candidate = measure(path, OwnerMode.RECENT, limit)
            assertEquals(baseline.facts, candidate.facts)
            assertEquals(baseline.proofs, candidate.proofs)
            assertEquals(baseline.omissions, candidate.omissions)
            assertEquals(baseline.confirmations, candidate.confirmations)
            assertEquals(baseline.examined, candidate.examined)
            assertEquals(baseline.pages, candidate.pages)
            val expected =
                when (path) {
                    WorkPath.PAIR,
                    WorkPath.REVERSE_PAIR,
                    WorkPath.REPEATED -> 1
                    WorkPath.ONE_HOP -> 1
                    WorkPath.DOMAIN_CHANGE,
                    WorkPath.OWNER_CHANGE,
                    WorkPath.DEFINITIONS -> 2
                }
            assertEquals(expected, candidate.preparations)
            println(
                "INVENTORY_WORK ${path.name} $limit ${baseline.preparations} ${candidate.preparations} " +
                    "${candidate.confirmations} ${candidate.examined} ${candidate.pages} ${candidate.facts.size}"
            )
        }
    }

    private enum class WorkPath {
        PAIR,
        REVERSE_PAIR,
        REPEATED,
        ONE_HOP,
        DOMAIN_CHANGE,
        OWNER_CHANGE,
        DEFINITIONS,
    }

    private enum class OwnerMode {
        DISABLED,
        RECENT,
    }

    private data class WorkOutput(
        val facts: List<String>,
        val preparations: Int,
        val confirmations: Int,
        val examined: Long,
        val pages: Int,
        val proofs: List<PageProof>,
        val omissions: List<RelationOmissionEvidence>,
    )

    private data class PageProof(
        val knownMinimum: Int,
        val limitations: Set<RelationLimitation>,
        val providerState: String,
    )

    private class Counts {
        val proofs = mutableListOf<PageProof>()
        val omissions = mutableListOf<RelationOmissionEvidence>()
        var preparations = 0
        var confirmations = 0
        var examined = 0L
        var pages = 0
    }

    private fun owner(mode: OwnerMode): IntellijReferenceInventoryReuse =
        when (mode) {
            OwnerMode.DISABLED -> IntellijReferenceInventoryReuse.Disabled
            OwnerMode.RECENT -> IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        }

    private fun questions(path: WorkPath, limit: Int): List<RelationRequest> {
        val seed = RelationReadTest().request(RelationMeaning.References, resultLimit = limit, workLimit = 128L)
        val selector = (seed.subject as RelationEndpoint.Subject).selector
        fun request(meaning: RelationMeaning, boundary: RelationSearchBoundary = seed.boundary) =
            RelationRequest.start(selector, meaning, seed.budget, boundary)
        val callers = request(RelationMeaning.Callers)
        return when (path) {
            WorkPath.PAIR,
            WorkPath.OWNER_CHANGE -> listOf(seed, callers)
            WorkPath.REVERSE_PAIR -> listOf(callers, seed)
            WorkPath.REPEATED -> listOf(seed, seed, callers)
            WorkPath.ONE_HOP -> listOf(seed)
            WorkPath.DOMAIN_CHANGE ->
                listOf(seed, request(RelationMeaning.Callers, RelationSearchBoundary.WORKSPACE_EXPANSION))
            WorkPath.DEFINITIONS -> listOf(request(RelationMeaning.Overrides), request(RelationMeaning.Overrides))
        }
    }

    private fun measure(path: WorkPath, mode: OwnerMode, limit: Int): WorkOutput {
        val counts = Counts()
        val retained = owner(mode)
        val facts = mutableListOf<RelationFact>()
        val requests = questions(path, limit)
        for (initial in requests) {
            val selected = if (path == WorkPath.OWNER_CHANGE) owner(mode) else retained
            val consumed = drain(initial, selected, counts)
            val expectedStarts = if (path == WorkPath.DEFINITIONS) emptySet() else (300 until 312).toSet()
            assertEquals(expectedStarts, consumed.map { it.occurrence.range.startInclusive }.toSet())
            assertEquals(expectedStarts.size, consumed.size)
            facts += consumed
        }
        return WorkOutput(
            facts.map { it.canonicalProjection() }.sorted(),
            counts.preparations,
            counts.confirmations,
            counts.examined,
            counts.pages,
            counts.proofs.toList(),
            counts.omissions.toList(),
        )
    }

    private fun drain(
        initial: RelationRequest,
        owner: IntellijReferenceInventoryReuse,
        counts: Counts,
    ): List<RelationFact> {
        val observed = mutableListOf<RelationFact>()
        var request = initial
        var pages = 0
        while (true) {
            val compilation = readPage(request, owner, counts)
            assertTrue(++pages <= 20, "A bounded locator set must drain")
            counts.pages++
            val batch =
                when (compilation) {
                    is RelationCompilation.Complete -> compilation.batch
                    is RelationCompilation.Qualified -> compilation.batch
                    is RelationCompilation.Rejected -> error("Unexpected scripted rejection: ${compilation.reason}")
                }
            assertTrue(batch.omissions.all { it.reason == RelationLimitation.RESULT_LIMIT_REACHED })
            assertTrue(batch.scopeExclusions.isEmpty())
            counts.omissions += batch.omissions
            assertTrue(batch.callbackObservations.isEmpty() && batch.callableObservations.isEmpty())
            observed += batch.facts
            counts.examined += batch.examinedWorkUnits.value
            if (compilation is RelationCompilation.Complete) {
                assertEquals(batch.semanticResultCount, compilation.coverage.exactCount.value)
                assertTrue(batch.omissions.isEmpty())
                return observed
            }
            val continuation =
                assertInstanceOf(
                        RelationIncompleteCoverage.Resumable::class.java,
                        (compilation as RelationCompilation.Qualified).coverage,
                    )
                    .continuation
            val coverage = compilation.coverage as RelationIncompleteCoverage.Resumable
            assertEquals(setOf(RelationLimitation.RESULT_LIMIT_REACHED), coverage.limitations)
            counts.proofs +=
                PageProof(
                    coverage.knownMinimum.value,
                    coverage.limitations,
                    continuation.providerState.canonicalProjection(),
                )
            request =
                RelationRequest.resume(
                        (initial.subject as RelationEndpoint.Subject).selector,
                        initial.meaning,
                        initial.budget,
                        continuation,
                        initial.boundary,
                    )
                    .refined()
        }
    }

    private fun readPage(
        request: RelationRequest,
        owner: IntellijReferenceInventoryReuse,
        counts: Counts,
    ): RelationCompilation {
        val collector = IntellijRelationCollector(request, { 0L })
        val termination =
            readRelationInventory(
                request,
                collector,
                prepare = {
                    counts.preparations++
                    val state = inventory(request)
                    assertTrue(collector.retainProviderState(state, preparedPartition = true))
                    RelationInventoryPreparation.Prepared(state)
                },
                confirm = { _, locator ->
                    counts.confirmations++
                    collector.accept(
                        RelationFact.create(
                                request,
                                request.subject,
                                request.subject,
                                RelationOccurrence.fromBoundary(
                                        locator.file,
                                        locator.range.startInclusive,
                                        locator.range.endExclusive,
                                    )
                                    .refined(),
                                RelationProvenance.K2_AUTHORED_SOURCE,
                            )
                            .refined()
                    )
                },
                cancellationCheck = {},
                clockNanoseconds = { 0L },
                inventories = owner,
            )
        return collector.finish(
            if (termination == ProviderTermination.HALTED) IntellijRelationTermination.Resumable(emptySet())
            else IntellijRelationTermination.Terminal
        )
    }

    private fun inventory(request: RelationRequest): RelationProviderState =
        when (RelationProviderKind.forMeaning(request.meaning)) {
            RelationProviderKind.INTELLIJ_REFERENCES_V2 ->
                RelationProviderState.references(
                    (300 until 312).map { offset ->
                        RelationProviderLocator.Reference(
                            request.subject.file,
                            ExactDeclarationTextRange.parse(offset, offset + 1).refined(),
                            RelationProviderItemDescriptor.parse("reference:$offset").refined(),
                        )
                    }
                )
            RelationProviderKind.INTELLIJ_DEFINITIONS_V2 -> RelationProviderState.definitions(emptyList())
            else -> error("Unsupported scripted provider")
        }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
