package io.github.amichne.kast.query.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryImpactAutomaticModelWitnessTest {
    @Test
    fun `automatic applications keep their native observation and application ordinals`() {
        val f = QueryImpactAutomaticModelFixture()
        val ledger = f.ledger().modelRefined()
        val count = QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.NATIVE_READS).modelRefined()
        assertEquals(8, count.value)
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, 8).modelRefined()
        assertEquals((0..7).toList(), view.entries.map { it.ordinal.value })
        assertEquals(
            listOf(
                QueryImpactWitnessEntry.NativeRead::class,
                QueryImpactWitnessEntry.CompilerTransfer::class,
                QueryImpactWitnessEntry.NativeRead::class,
                QueryImpactWitnessEntry.CompilerTransfer::class,
                QueryImpactWitnessEntry.FlowObligation::class,
                QueryImpactWitnessEntry.ValueModelApplication::class,
                QueryImpactWitnessEntry.NativeRead::class,
                QueryImpactWitnessEntry.NativeRead::class,
            ),
            view.entries.map { it.evidence::class },
        )
        val application = view.entries[5].evidence as QueryImpactWitnessEntry.ValueModelApplication
        assertEquals(1, application.observationOrdinal.value)
        assertEquals(0, application.applicationOrdinal.value)
        assertSame(f.application, application.application)
        assertEquals(0, QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.MODELS).modelRefined().value)
    }
}
