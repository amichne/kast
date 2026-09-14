package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationTimeAdmissionTest {
    @Test
    fun `time alone stops compiler effects before first item or after an examined prefix`() {
        for (prefixSize in 0..1) {
            val fixture = RelationReadTest()
            val request = fixture.request(RelationMeaning.References)
            var now = 0L
            var compilerCalls = 0
            val collector = IntellijRelationCollector(request, clockNanoseconds = { now })
            for (position in 0..1) {
                if (position == prefixSize) now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
                when (collector.beginProviderItem(fixture.providerItem("item-$position"))) {
                    IntellijRelationProviderItemAdmission.READY -> {
                        compilerCalls += 1
                        assertTrue(collector.examineIncomplete(RelationLimitation.UNRESOLVED_TARGET))
                    }
                    IntellijRelationProviderItemAdmission.HALTED -> break
                    else -> error("Fresh provider cursor must be admitted or halted")
                }
            }
            val result =
                collector.finish(IntellijRelationTermination.Resumable(emptySet())) as RelationCompilation.Qualified
            assertEquals(prefixSize, compilerCalls)
            assertEquals(prefixSize.toLong(), result.batch.examinedWorkUnits.value)
            assertTrue(RelationLimitation.TIME_LIMIT_REACHED in result.coverage.limitations)
            assertTrue(RelationLimitation.WORK_LIMIT_REACHED !in result.coverage.limitations)
            assertTrue(RelationLimitation.RESULT_LIMIT_REACHED !in result.coverage.limitations)
            when (val coverage = result.coverage) {
                is RelationIncompleteCoverage.Resumable -> {
                    assertEquals(1, prefixSize)
                    assertEquals(1L, coverage.continuation.nextProviderCursor.nextPosition.value)
                    assertTrue(RelationLimitation.UNRESOLVED_TARGET in coverage.limitations)
                }
                is RelationIncompleteCoverage.TerminalIncomplete -> {
                    assertEquals(0, prefixSize)
                    assertTrue(RelationLimitation.PROVIDER_STALLED in coverage.limitations)
                }
            }
        }
    }
}
