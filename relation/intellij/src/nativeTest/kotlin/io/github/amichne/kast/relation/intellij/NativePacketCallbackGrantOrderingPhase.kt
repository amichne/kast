package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** The phase starts after the native reader's initial unit and first successful permit. */
internal class NativePacketCallbackGrantOrderingPhase(
    private val fixture: NativePacketValueFixture,
    private val request: ValueFlowRequest,
) {
    private val allowance = IntellijRelationAllowance { System.nanoTime() }
    private val counter = GrantOrderingCounter(allowance)

    fun read(project: com.intellij.openapi.project.Project): GrantOrderingCapture {
        val domain = RelationRequest.start(request.source.enclosing, RelationMeaning.References, request.budget)
        val prepared = IntellijValueFlowCompilerAdapter(counter).prepare(project, domain, fixture.model)
        assertTrue("Real callback phase setup failed", prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready)
        val ready = prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
        allowance.examine()
        assertEquals(CallbackWorkAdmission.READY, allowance.admitCallbackWork(request.budget.resources))
        assertEquals(OUTER_WORK, allowance.examined)
        val retained =
            NativeValueFlowTransfers(
                request = request,
                owner = ready.owner,
                scope = ready.scope,
                observation = counter,
            )
        val owner =
            NativeValueCallbackFlow(
                request = request,
                role = request.source.role as ValueRole.FunctionArgument,
                scope = ready.scope,
                projection = ready.projection,
                allowance = allowance,
                retained = retained,
                permit = { permit(retained) },
            )
        val observed = owner.observe()
        proveQualifiedWorkRefusal(observed)
        counter.markRefusal()
        val result = owner.consume(observed)
        return GrantOrderingCapture(
            result = result,
            work = allowance.examined,
            sessions = counter.sessions,
            afterRefusalSessions = counter.afterRefusalSessions,
            transfers = retained.edges.toList(),
            obligations = retained.obligations.toList(),
            sessionWork = counter.sessionWork.toList(),
        )
    }

    private fun proveQualifiedWorkRefusal(observed: NativeValueCallbackObservation) {
        assertTrue(
            "The original supply did not reach its actual callback proof",
            observed is NativeValueCallbackObservation.Read,
        )
        val callback = (observed as NativeValueCallbackObservation.Read).callback
        assertTrue(
            "The callback proof did not return an observed qualified flow",
            callback is CallbackInvocationFlowRead.Observed,
        )
        val flow = (callback as CallbackInvocationFlowRead.Observed).flow
        val invocation = (request.source.role as ValueRole.FunctionArgument).call
        val selected =
            flow.invocations.count {
                it.occurrence.range == invocation.range && it.occurrence.file == request.source.enclosing.file
            }
        assertTrue(flow.binding is CallbackBindingEvidence.Bound)
        assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
        assertEquals(1, flow.invocations.size)
        assertEquals(1, selected)
        assertEquals(setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), flow.obligations)
        println(
            "CALLBACK_GRANT96_REFUSAL binding=BOUND scan=${flow.scan} invocations=1 selected=1 " +
                "causes=${flow.obligations} work=${allowance.examined}"
        )
    }

    private fun permit(retained: NativeValueFlowTransfers): Boolean =
        when (allowance.admitCallbackWork(request.budget.resources)) {
            CallbackWorkAdmission.READY -> true
            CallbackWorkAdmission.WORK_LIMIT_REACHED -> {
                retained.unresolved(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
                false
            }
            CallbackWorkAdmission.TIME_LIMIT_REACHED -> {
                retained.unresolved(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
                false
            }
        }

    private companion object {
        const val OUTER_WORK = 2L
    }
}

internal data class GrantOrderingCapture(
    val result: Refinement<Unit, ValueFlowRejection>,
    val work: Long,
    val sessions: Int,
    val afterRefusalSessions: Int,
    val transfers: List<ValueTransfer>,
    val obligations: List<ValueFlowObligation>,
    val sessionWork: List<Long>,
)

private class GrantOrderingCounter(private val allowance: IntellijRelationAllowance) : IntellijReadObservation {
    var sessions = 0
        private set

    var afterRefusalSessions = 0
        private set

    val sessionWork = mutableListOf<Long>()
    private var phase = GrantOrderingPhase.SCAN

    fun markRefusal() {
        check(phase == GrantOrderingPhase.SCAN)
        phase = GrantOrderingPhase.AFTER_QUALIFIED_WORK_REFUSAL
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.K2_ANALYSIS) {
            sessions++
            if (phase == GrantOrderingPhase.AFTER_QUALIFIED_WORK_REFUSAL) afterRefusalSessions++
            if (sessionWork.size < MAX_SESSION_RECORDS) sessionWork += allowance.examined
        }
        return IntellijReadCallScope.None
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

    private companion object {
        const val MAX_SESSION_RECORDS = 32
    }
}

private enum class GrantOrderingPhase {
    SCAN,
    AFTER_QUALIFIED_WORK_REFUSAL,
}
