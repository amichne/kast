package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationRetainedCoverageTest {
    private val fixture = RelationReadTest()

    @Test
    fun `pagination retains earlier provider coverage loss after the result limit clears`() {
        val initial = fixture.request(RelationMeaning.References)
        val first = IntellijRelationCollector(initial, { 0L })
        first.beginProviderItem(fixture.providerItem("unsupported"))
        first.examineIncomplete(RelationLimitation.UNSUPPORTED_ITEM)
        val page =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                first.finish(IntellijRelationTermination.Resumable(setOf(RelationLimitation.RESULT_LIMIT_REACHED))),
            )
        assertEquals(
            1L,
            (page.batch.omissions.single { it.reason == RelationLimitation.UNSUPPORTED_ITEM }.measurement
                    as RelationOmissionMeasurement.ObservedOnPage)
                .items
                .value,
        )
        val continuation =
            assertInstanceOf(
                    RelationIncompleteCoverage.Resumable::class.java,
                    page.coverage,
                )
                .continuation
        val resumed =
            RelationRequest.resume(
                    (initial.subject as RelationEndpoint.Subject).selector,
                    initial.meaning,
                    initial.budget,
                    continuation,
                )
                .refined()
        val last = IntellijRelationCollector(resumed, { 0L })
        last.beginProviderItem(fixture.providerItem("unsupported"))
        val result =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                last.finish(IntellijRelationTermination.Terminal),
            )
        assertEquals(setOf(RelationLimitation.UNSUPPORTED_ITEM), result.coverage.limitations)
        assertTrue(result.batch.omissions.single().measurement is RelationOmissionMeasurement.UnmeasuredOnPage)
        assertInstanceOf(
            RelationIncompleteCoverage.TerminalIncomplete::class.java,
            result.coverage,
        )
    }

    @Test
    fun `deadline before first committed item reports a terminal provider stall`() {
        val request = fixture.request(RelationMeaning.Callees)
        var now = 0L
        val collector = IntellijRelationCollector(request, clockNanoseconds = { now })
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertEquals(IntellijRelationProviderEnumerationAdmission.HALTED, collector.admitProviderEnumeration())
        val result =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                collector.finish(IntellijRelationTermination.Resumable(setOf(RelationLimitation.TIME_LIMIT_REACHED))),
            )
        assertInstanceOf(
            RelationIncompleteCoverage.TerminalIncomplete::class.java,
            result.coverage,
        )
        assertEquals(
            setOf("TIME_LIMIT_REACHED", "PROVIDER_STALLED"),
            result.coverage.limitations.map { it.name }.toSet(),
        )
        assertTrue(result.batch.facts.isEmpty())
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
