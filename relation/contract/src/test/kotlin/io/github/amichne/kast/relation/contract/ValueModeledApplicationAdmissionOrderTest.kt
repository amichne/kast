package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A known foreign application source must reject before detached capacity arithmetic. */
class ValueModeledApplicationAdmissionOrderTest {
    @Test
    fun `wrong selected call rejects for source mismatch despite a tiny byte grant`() {
        val f = ReviewedModelOracleCase()
        val source = f.receiver()
        val otherCall = f.call(start = 45)
        val binding =
            ReviewedLibraryBinding.fromCompiler(otherCall, ReviewedValueModel.FirstOrNull, f.artifact).flowRefined()
        val other =
            ValueModelApplication.CollectionSelection.selectFirst(f.receiver(call = otherCall), binding).flowRefined()
        assertEquals(Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH), step(source, other))
    }

    @Test
    fun `the same tiny byte grant still refuses a correctly bound application for capacity`() {
        val f = ReviewedModelOracleCase()
        val source = f.receiver()
        val application = ValueModelApplication.CollectionSelection.selectFirst(source, f.binding()).flowRefined()
        assertEquals(Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED), step(source, application))
    }

    private fun step(source: ValueSite, application: ValueModelApplication) =
        ValueFlowStep.fromCompiler(
            source = source,
            transfers = emptyList(),
            obligations = emptyList(),
            terminal = ValueFlowTerminal.SupportedDomainExhausted,
            domain =
                RelationRequest.start(
                    source.enclosing,
                    RelationMeaning.References,
                    RelationBudget(
                        ResourceBudget(
                            ResultLimit.parse(4).flowRefined(),
                            WorkUnitLimit.parse(100).flowRefined(),
                            ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                        ),
                        RelationByteLimit.parse(1).flowRefined(),
                    ),
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                ),
            examinedWorkUnits = RelationWorkCount.parse(1).flowRefined(),
            applications = listOf(application),
        )
}
