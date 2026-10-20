package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueReviewedCallbackTarget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.util.EnumMap
import kotlinx.coroutines.runBlocking

/** Physical sharing follows fresh full-proof admission. It never substitutes for current compiler or artifact work. */
class NativePacketLetFrameSharingTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-let-frame-sharing-jdk"

    fun testFreshReadmittedReturnReusesItsExactRetainedFrame() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketLetCallbackSource(project, getOrCreateProjectBaseDir(), fixture)
        val facts = source.original()
        val normal = source.normalReturn(facts)
        val expected = normal.callStack.frames.last()
        val counters = LetReturnReadCounters()
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter(observation = counters)
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(normal, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Current native return admission must succeed", read is ValueFlowRead.Observed)
        val step = (read as ValueFlowRead.Observed).step
        val application = step.applications.single() as ValueModelApplication.ScopeCallbackReturn
        assertEquals(expected, application.frame)
        assertEquals(facts.binding, application.binding)
        assertEquals(normal.valuePath, application.target.valuePath)
        assertEquals(normal.predicates, application.target.predicates)
        assertEquals(normal.callStack.frames.dropLast(1), application.target.callStack.frames)
        assertTrue("Frame reuse still needs fresh K2 work", counters.sessions > 0)
        assertTrue(counters[IntellijReadCounter.VALUE_MODEL_ARTIFACT_READS] > 0)
        assertTrue(counters[IntellijReadCounter.VALUE_MODEL_ARTIFACT_ADMITTED] > 0)
        println(
            "LET_FRAME_PHYSICAL shared=${expected === application.frame} " +
                "sourceBytes=${normal.retainedBytes} stepBytes=${step.retainedBytes} " +
                "nativeWork=${step.examinedWorkUnits.value} k2Sessions=${counters.sessions} " +
                "artifactReads=${counters[IntellijReadCounter.VALUE_MODEL_ARTIFACT_READS]} " +
                "artifactAdmitted=${counters[IntellijReadCounter.VALUE_MODEL_ARTIFACT_ADMITTED]}"
        )
        assertSame("An equal freshly admitted frame must reuse the retained full proof", expected, application.frame)
        assertSame((expected.target as ValueReviewedCallbackTarget).binding, application.binding)
    }
}

private class LetReturnReadCounters : IntellijReadObservation by IntellijReadObservation.None {
    private val counters = EnumMap<IntellijReadCounter, Int>(IntellijReadCounter::class.java)
    var sessions = 0
        private set

    operator fun get(counter: IntellijReadCounter): Int = counters[counter] ?: 0

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = get(counter) + amount
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.K2_ANALYSIS) sessions++
        return IntellijReadCallScope.None
    }
}
