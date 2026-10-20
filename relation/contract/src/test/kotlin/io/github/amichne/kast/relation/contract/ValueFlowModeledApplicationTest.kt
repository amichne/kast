package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached step admission and capacity laws, separate from native library behavior. */
class ValueFlowModeledApplicationTest {
    @Test
    fun `result limit counts both native transfers and reviewed applications`() {
        val f = ModeledStepCase()
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED),
            f.step(resultLimit = 1),
        )
        val step = f.step().flowRefined()
        assertEquals(2, step.receipts.single().returnedResults.value)
        assertEquals(listOf(f.edge), step.transfers)
        assertEquals(listOf(f.application), step.applications)
    }

    @Test
    fun `application source cannot borrow another selected invocation`() {
        val f = ModeledStepCase()
        val otherCall = f.model.call(start = 45)
        val otherBinding =
            ReviewedLibraryBinding.fromCompiler(otherCall, ReviewedValueModel.FirstOrNull, f.model.artifact)
                .flowRefined()
        val other =
            ValueModelApplication.CollectionSelection.selectFirst(f.model.receiver(call = otherCall), otherBinding)
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH),
            f.step(applications = listOf(other)),
        )
    }

    @Test
    fun `final detached capacity includes the actual application proof`() {
        val f = ModeledStepCase()
        val step = f.step().flowRefined()
        val without = f.step(applications = emptyList()).flowRefined()
        assertTrue(step.retainedBytes > without.retainedBytes)
        assertEquals(step.retainedBytes, step.receipts.single().returnedBytes.value)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED),
            f.step(byteLimit = step.retainedBytes - 1),
        )
        assertEquals(step.retainedBytes, f.step(byteLimit = step.retainedBytes).flowRefined().retainedBytes)
    }

    @Test
    fun `step snapshots applications and keeps proof through callable sharing`() {
        val f = ModeledStepCase()
        val mutable = mutableListOf<ValueModelApplication>(f.application)
        val step = f.step(applications = mutable).flowRefined()
        mutable.clear()
        val shared = step.shareCallableEvidence(listOf(step))
        assertEquals(listOf(f.application), step.applications)
        assertEquals(step.applications, shared.applications)
        val application = shared.applications.single() as ValueModelApplication.CollectionSelection
        assertEquals(f.application.binding, application.binding)
        assertSame(f.application.condition, application.condition)
        assertEquals(f.source.callStack, application.target.callStack)
        assertEquals(f.source.predicates, application.target.predicates)
    }
}

private class ModeledStepCase {
    val model = ReviewedModelOracleCase()
    val source = model.receiver()
    val application = ValueModelApplication.CollectionSelection.selectFirst(source, model.binding()).flowRefined()
    private val nativeTarget =
        ValueSite.fromCompiler(
                enclosing = source.enclosing,
                range = flowRange(54, 56),
                role = ValueRole.LocalBinding,
                executionOwner = source.executionOwner,
                valuePath = source.valuePath,
                callStack = source.callStack,
                predicates = source.predicates,
            )
            .flowRefined()
    val edge = ValueTransfer.fromCompiler(source, nativeTarget, ValueTransferKind.LOCAL_BINDING).flowRefined()

    fun step(
        resultLimit: Int = 4,
        byteLimit: Long = 4_000_000,
        applications: List<ValueModelApplication> = listOf(application),
    ) =
        ValueFlowStep.fromCompiler(
            source = source,
            transfers = listOf(edge),
            obligations = emptyList(),
            terminal = ValueFlowTerminal.SupportedDomainExhausted,
            domain = domain(resultLimit, byteLimit),
            examinedWorkUnits = RelationWorkCount.parse(1).flowRefined(),
            applications = applications,
        )

    private fun domain(resultLimit: Int, byteLimit: Long) =
        RelationRequest.start(
            source.enclosing,
            RelationMeaning.References,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(resultLimit).flowRefined(),
                    WorkUnitLimit.parse(100).flowRefined(),
                    ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                ),
                RelationByteLimit.parse(byteLimit).flowRefined(),
            ),
            RelationSearchBoundary.WORKSPACE_EXPANSION,
        )
}
