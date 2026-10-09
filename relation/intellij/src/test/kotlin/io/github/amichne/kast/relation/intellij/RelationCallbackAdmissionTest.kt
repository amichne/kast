package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class RelationCallbackAdmissionTest {
    @Test
    fun `callbacks can reject expired work before any excluded site is inspected`() {
        val request = RelationReadTest().request(RelationMeaning.References)
        var now = 0L
        var cancellations = 0
        val collector = IntellijRelationCollector(request, clockNanoseconds = { now })
        repeat(10) {
            assertEquals(
                IntellijRelationProviderEnumerationAdmission.READY,
                collector.admitProviderCallback { cancellations++ },
            )
        }
        assertEquals(IntellijRelationProviderEnumerationAdmission.READY, collector.admitProviderCandidate())
        now = 1_000_000_000L
        assertEquals(
            IntellijRelationProviderEnumerationAdmission.HALTED,
            collector.admitProviderCallback { cancellations++ },
        )
        assertEquals(11, cancellations)
        val result =
            assertInstanceOf<RelationCompilation.Qualified>(
                collector.finish(IntellijRelationTermination.Resumable(emptySet()))
            )
        assertEquals(setOf(RelationLimitation.TIME_LIMIT_REACHED), result.coverage.limitations)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
    }
}
