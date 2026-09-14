package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationCooperativeBudgetTest {
    @Test
    fun `exhausted semantic work prevents admission of another compiler unit and retains omission coverage`() {
        val fixture = RelationReadTest()
        val request = fixture.request(RelationMeaning.References, workLimit = 1)
        val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })
        assertEquals(
            IntellijRelationProviderItemAdmission.READY,
            collector.beginProviderItem(fixture.providerItem("first")),
        )
        assertTrue(collector.examineIncomplete(RelationLimitation.UNRESOLVED_TARGET))
        assertEquals(
            IntellijRelationProviderItemAdmission.HALTED,
            collector.beginProviderItem(fixture.providerItem("second")),
        )
        val result =
            collector.finish(IntellijRelationTermination.Resumable(emptySet())) as RelationCompilation.Qualified
        val coverage = result.coverage as RelationIncompleteCoverage.Resumable
        assertEquals(1L, result.batch.examinedWorkUnits.value)
        assertEquals(1L, coverage.continuation.nextProviderCursor.nextPosition.value)
        assertTrue(RelationLimitation.UNRESOLVED_TARGET in coverage.limitations)
        assertTrue(RelationLimitation.WORK_LIMIT_REACHED in coverage.limitations)
    }
}
