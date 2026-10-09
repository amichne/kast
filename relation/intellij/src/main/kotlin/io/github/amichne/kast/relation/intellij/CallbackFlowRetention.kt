package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Alias routes, detached invocations, and owner bindings share one original result and returned-byte grant. */
internal class CallbackFlowRetention(
    private val budget: RelationBudget,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    private var retainedBytes = 0L
    private var retainedResults = 0
    private val formals = CallbackRetainedRecords<CallbackParameterIdentity>(this) { it.retainedBytes }
    private val forwardings = CallbackRetainedRecords<CallbackParameterForwarding>(this) { it.retainedBytes }
    private val invocations = CallbackRetainedRecords<CallbackParameterInvocation>(this) { it.retainedBytes }
    private val owners = CallbackRetainedRecords<CallbackBodyBinding>(this) { it.retainedBytes }

    fun admitFormal(formal: CallbackParameterIdentity) = formals.admit(formal)

    fun admitForwarding(forwarding: CallbackParameterForwarding) = forwardings.admit(forwarding)

    fun admitInvocation(invocation: CallbackParameterInvocation) = invocations.admit(invocation)

    fun admitOwner(owner: CallbackBodyBinding) = owners.admit(owner)

    /** Copies of containers share immutable records only when this grant already owns those exact objects. */
    fun admitSummary(summary: CallbackParameterSummary): Refinement<Unit, CallbackInvocationFlowCause> =
        when (val storage = summaryStorage(summary)) {
            CallbackSummaryStorage.STANDALONE -> admit(summary.retainedBytes)
            CallbackSummaryStorage.SHARED_RECORDS ->
                admit(
                    SUMMARY_CONTAINER_BYTES +
                        SUMMARY_REFERENCE_BYTES *
                            (summary.invocations.size + summary.ownerBindings.size + summary.obligations.size)
                )
        }

    private fun summaryStorage(summary: CallbackParameterSummary): CallbackSummaryStorage {
        val graphOwned =
            when (val evidence = summary.forwarding) {
                CallbackForwardingEvidence.InvocationRoutes -> true
                is CallbackForwardingEvidence.ExhaustedGraph ->
                    formals.owns(evidence.graph.root) && evidence.graph.forwardings.all(forwardings::owns)
            }
        val recordsOwned = summary.invocations.all(invocations::owns) && summary.ownerBindings.all(owners::owns)
        return if (formals.owns(summary.formal) && recordsOwned && graphOwned) CallbackSummaryStorage.SHARED_RECORDS
        else CallbackSummaryStorage.STANDALONE
    }

    fun admit(bytes: Long): Refinement<Unit, CallbackInvocationFlowCause> {
        val requiredBytes = if (bytes > Long.MAX_VALUE - retainedBytes) Long.MAX_VALUE else retainedBytes + bytes
        observe(IntellijReadGauge.CALLBACK_PROOF_BYTE_ALLOWANCE, budget.returnedBytes.value)
        observe(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES, requiredBytes)
        val admitted =
            when {
                retainedResults >= budget.resources.resultLimit.value -> {
                    observation.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_RESULT_REJECTED)
                    Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED)
                }
                bytes > budget.returnedBytes.value - retainedBytes -> {
                    observeByteRejection(requiredBytes)
                    observation.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_BYTE_REJECTED)
                    Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED)
                }
                else -> {
                    retainedBytes += bytes
                    retainedResults += 1
                    observation.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_ADMITTED)
                    Refinement.Refined(Unit)
                }
            }
        observe(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES, retainedBytes)
        return admitted
    }

    private fun observeByteRejection(requiredBytes: Long) {
        observe(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_ALLOWANCE, budget.returnedBytes.value)
        observe(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_RETAINED_BYTES, retainedBytes)
        observe(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES, requiredBytes)
    }

    private fun observe(gauge: IntellijReadGauge, bytes: Long) =
        when (val admitted = IntellijReadGaugeValue.parse(bytes)) {
            is Refinement.Refined -> observation.measure(gauge, admitted.value)
            is Refinement.Rejected -> error("Admitted allowance and conservative callback storage cannot be negative")
        }
}

private const val SUMMARY_CONTAINER_BYTES = 4096L
private const val SUMMARY_REFERENCE_BYTES = 64L

private enum class CallbackSummaryStorage {
    STANDALONE,
    SHARED_RECORDS,
}
