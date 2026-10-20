package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import kotlinx.coroutines.runBlocking

/** Native model assertions use the frozen supplemental positions. Original S2 keeps its original source policy. */
class NativePacketFirstOrNullApplicationTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-first-or-null-application-jdk"

    fun testOriginalElementZeroGetsConditionalReviewedSelection() =
        assertApplication(NativePacketFirstOrNullModelRows.Row.ORIGINAL_ELEMENT_ZERO)

    fun testOriginalAnyElementGetsConditionalReviewedSelection() =
        assertApplication(NativePacketFirstOrNullModelRows.Row.ORIGINAL_ANY_ELEMENT)

    fun testOriginalElementOneGetsNoReviewedSelection() =
        assertApplication(NativePacketFirstOrNullModelRows.Row.ORIGINAL_ELEMENT_ONE)

    fun testSourceDefinedSameNameGetsNoReviewedSelection() =
        assertApplication(NativePacketFirstOrNullModelRows.Row.SOURCE_SAME_NAME)

    private fun assertApplication(row: NativePacketFirstOrNullModelRows.Row) {
        val paths =
            if (row == NativePacketFirstOrNullModelRows.Row.SOURCE_SAME_NAME)
                listOf(NativePacketFirstOrNullFrontierSource.CONTROL)
            else NativePacketCompositeSuccessSource.PATHS
        val fixture = packetFixture(paths)
        val expected = NativePacketFirstOrNullFrontierSource(project, getOrCreateProjectBaseDir(), fixture)
        val source =
            ReadAction.compute<ValueSite, RuntimeException> {
                val receiver =
                    if (row == NativePacketFirstOrNullModelRows.Row.SOURCE_SAME_NAME) expected.sourceDefined()
                    else expected.original()
                receiver.withState(valuePath = NativePacketFirstOrNullModelRows.inputPath(row)).nativeRefined()
            }
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, expected.boundary),
                    model = fixture.model,
                )
        }
        assertTrue("The real K2 receiver needs an observed step: $read", read is ValueFlowRead.Observed)
        val step = (read as ValueFlowRead.Observed).step
        when (val target = NativePacketFirstOrNullModelRows.requiredResult(row, source)) {
            NativePacketFirstOrNullModelRows.Result.NoApplication -> assertTrue(step.applications.isEmpty())
            is NativePacketFirstOrNullModelRows.Result.Selected -> assertSelected(source, target.site, step)
        }
        println("FIRST_OR_NULL_APPLICATION row=$row applications=${step.applications.size} originalS2=UNCHANGED")
    }

    private fun assertSelected(source: ValueSite, target: ValueSite, step: ValueFlowStep) {
        assertEquals("The reviewed native selection is missing. Causes=${step.obligations}", 1, step.applications.size)
        val selected = step.applications.single() as ValueModelApplication.CollectionSelection
        assertEquals(source, selected.source)
        assertEquals(target, selected.target)
        assertEquals(source.predicates, selected.target.predicates)
        assertEquals(source.callStack, selected.target.callStack)
        assertEquals((source.role as ValueRole.Receiver).call, selected.binding.invocation)
        assertSame(ReviewedValueModel.FirstOrNull, selected.binding.model)
        assertEquals(0, selected.condition.index.value)
        assertEquals(source.withState(valuePath = target.valuePath).nativeRefined(), selected.condition.receiver)
        assertEquals(target, selected.condition.result)
        assertEquals(ReviewedValueModel.FirstOrNull.artifact.binaryDigest, selected.binding.artifact.binaryDigest)
        assertEquals(ReviewedValueModel.FirstOrNull.artifact.version, selected.binding.artifact.version)
        assertTrue(
            "A reviewed selection must not become a compiler transfer",
            step.transfers.none { it.target == target },
        )
    }
}
