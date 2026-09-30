package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Collector/state rules only. Installed fixtures establish native search and K2 identity behavior. */
class ReferenceProgressTest {
    @Test
    fun `retained occurrences exhaust limits one five and twenty without prefix replay`() {
        for (limit in listOf(1, 5, 20)) assertExhaustion(limit)
    }

    private fun assertExhaustion(limit: Int) {
        val initial = RelationReadTest().request(RelationMeaning.References, resultLimit = limit)
        val locators = locators(initial)
        var retained = RelationProviderState.references(locators)
        var request = initial
        val observed = mutableListOf<RelationReferenceOccurrence>()
        val observation = PrefixObservation()
        var pages = 0
        while (true) {
            val read = consumePage(request, retained, observation, pages == 0)
            val batch =
                when (val page = read) {
                    is RelationCompilation.Complete -> page.batch
                    is RelationCompilation.Qualified -> page.batch
                    is RelationCompilation.Rejected -> error(page.reason.toString())
                }
            assertTrue(batch.facts.isEmpty())
            observed += batch.referenceOccurrences
            assertTrue(++pages <= 62)
            if (read is RelationCompilation.Complete) break
            val continuation =
                ((read as RelationCompilation.Qualified).coverage as RelationIncompleteCoverage.Resumable).continuation
            retained = continuation.providerState
            request =
                RelationRequest.resume(
                        (initial.subject as RelationEndpoint.Subject).selector,
                        initial.meaning,
                        initial.budget,
                        continuation,
                    )
                    .value()
        }
        assertEquals(61, observed.size)
        assertEquals(61, observed.map { it.occurrence.range }.distinct().size)
        assertEquals(locators.map { it.range }.toSet(), observed.map { it.occurrence.range }.toSet())
        assertEquals(0, observation.replayedPrefix)
    }

    private fun locators(request: RelationRequest): List<RelationProviderLocator.Reference> =
        (0 until 61).flatMap { ordinal ->
            listOf("first-provider", "second-provider").map { provider ->
                RelationProviderLocator.Reference(
                    request.subject.file,
                    ExactDeclarationTextRange.parse(ordinal * 2, ordinal * 2 + 1).value(),
                    RelationProviderItemDescriptor.parse("$provider:$ordinal").value(),
                )
            }
        }

    private fun consumePage(
        request: RelationRequest,
        state: RelationProviderState,
        observation: PrefixObservation,
        first: Boolean,
    ): RelationCompilation {
        val collector = IntellijRelationCollector(request, { 0L }, observation)
        val provider =
            readRelationInventory(
                request,
                collector,
                prepare = {
                    assertTrue(first, "A successor must never rebuild its native inventory")
                    assertTrue(collector.retainProviderState(state, preparedPartition = true))
                    RelationInventoryPreparation.Prepared(state)
                },
                confirm = { retained, locator ->
                    if (retained.alreadyConfirmed(locator)) collector.dismissProviderItem()
                    else {
                        observeRelationLocatorRestoration(request, retained.consumedLocatorCount, observation)
                        collector.acceptReference(occurrence(request, locator))
                    }
                },
                cancellationCheck = {},
                observation = observation,
            )
        val termination =
            if (provider == ProviderTermination.HALTED) IntellijRelationTermination.Resumable(emptySet())
            else IntellijRelationTermination.Terminal
        return collector.finish(termination)
    }

    private fun occurrence(request: RelationRequest, locator: RelationProviderLocator): RelationReferenceOccurrence =
        RelationReferenceOccurrence.confirmed(
                request,
                RelationConfirmedReferenceTarget.fromCompiler(request.subject, request.subject.compilerIdentity)
                    .value(),
                RelationOccurrence.fromBoundary(locator.file, locator.range.startInclusive, locator.range.endExclusive)
                    .value(),
                RelationReferenceContext.IMPORT,
                RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .value()

    private class PrefixObservation : IntellijReadObservation {
        var replayedPrefix = 0

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            if (counter == IntellijReadCounter.RELATION_REPLAYED_PREFIX) replayedPrefix += amount
        }
    }

    @Test
    fun `a proven occurrence completed after deadline remains in its qualified result with a usable remainder`() {
        val request = RelationReadTest().request(RelationMeaning.References)
        var now = 0L
        val collector = IntellijRelationCollector(request, { now })
        val locators =
            listOf(0, 1).map {
                RelationProviderLocator.Reference(
                    request.subject.file,
                    ExactDeclarationTextRange.parse(it * 2, it * 2 + 1).value(),
                    RelationProviderItemDescriptor.parse("reference:$it").value(),
                )
            }
        val retained = RelationProviderState.references(locators)
        collector.retainProviderState(retained, preparedPartition = true)
        collector.beginProviderItem(locators.first().descriptor)
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(request.subject, request.subject.compilerIdentity).value()
        val occurrence =
            RelationReferenceOccurrence.confirmed(
                    request,
                    target,
                    RelationOccurrence.fromBoundary(request.subject.file, 0, 1).value(),
                    RelationReferenceContext.IMPORT,
                    RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .value()
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertFalse(collector.acceptReference(occurrence))
        collector.retainProviderState(retained.consume())
        val result =
            collector.finish(IntellijRelationTermination.Resumable(emptySet())) as RelationCompilation.Qualified
        assertEquals(listOf(occurrence), result.batch.referenceOccurrences)
        assertEquals(setOf(RelationLimitation.TIME_LIMIT_REACHED), result.coverage.limitations)
        assertTrue(
            (result.coverage as RelationIncompleteCoverage.Resumable).continuation.providerState.hasUnfinishedWork
        )
    }

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}
