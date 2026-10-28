package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody

internal enum class CallbackSupplierActivation {
    AVAILABLE,
    CAPACITY_EXHAUSTED,
}

/** Detached native observations; domain admission follows after the supplier's independent activation read. */
internal data class CallbackFormalScanSnapshot(
    val invocations: List<CallbackParameterInvocation>,
    val obligations: Set<CallbackInvocationFlowCause>,
    val owners: List<CallbackBodyBinding>,
    val scan: CallbackInvocationScan,
    val forwarding: CallbackForwardingEvidence,
) {
    fun supplierActivation(): CallbackSupplierActivation =
        if (
            obligations.any {
                it == CallbackInvocationFlowCause.RESULT_LIMIT_REACHED ||
                    it == CallbackInvocationFlowCause.BYTE_LIMIT_REACHED
            }
        )
            CallbackSupplierActivation.CAPACITY_EXHAUSTED
        else CallbackSupplierActivation.AVAILABLE

    fun withSupplier(
        causes: Set<CallbackInvocationFlowCause>,
        additional: List<CallbackBodyBinding> = emptyList(),
    ): CallbackFormalScanSnapshot {
        val combined = obligations + causes
        val qualifiedScan =
            if (
                scan == CallbackInvocationScan.EXHAUSTIVE &&
                    combined.all { it == CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }
            )
                scan
            else CallbackInvocationScan.INCOMPLETE
        return copy(obligations = combined, owners = owners + additional, scan = qualifiedScan)
    }
}

private data class CallbackSummaryScan(
    val evidence: CallbackFormalScanSnapshot,
    val candidate: CallbackSummaryCandidate,
)

/** Capture formal closure before applying each supplier; a failed activation cannot erase the captured graph. */
internal class IntellijCallbackFlowRead(
    private val context: IntellijCallbackFlowContext,
    private val prepared: PreparedCallbackFlow,
    private val body: RelationCallableBody.Anonymous,
    private val summaries: CallbackParameterSummaries,
) {
    private val reuse = CallbackSummaryReuse(summaries)

    fun read(): CallbackInvocationFlowRead {
        val formal =
            when (val admitted = prepared.formal()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return CallbackInvocationFlowRead.Unavailable(admitted.failure)
            }
        val staged =
            when (val admitted = formalScan(formal)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(admitted.failure)
            }
        val evidence =
            when (
                val supplied =
                    observeCallbackCoverage(
                        context.observation,
                        CallbackCoverageStage.SUPPLIER,
                        { it },
                    ) {
                        when (
                            val read =
                                IntellijCallbackSupplierExecution(context, prepared, summaries).read(staged.evidence)
                        ) {
                            is Refinement.Refined -> Refinement.Refined(read.value.withSupplierQualification())
                            is Refinement.Rejected -> read
                        }
                    }
            ) {
                is Refinement.Refined -> supplied.value
                is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(supplied.failure)
            }
        val result =
            context.observed(
                body = body,
                binding = prepared.binding,
                invocations = evidence.invocations,
                obligations = evidence.obligations,
                scan = evidence.scan,
                forwarding = evidence.forwarding,
            )
        return reuse.publish(result, evidence.owners, staged.candidate)
    }

    private fun formalScan(
        formal: CallbackParameterIdentity
    ): Refinement<CallbackSummaryScan, CallbackInvocationFlowFailure> =
        observeCallbackCoverage(
            context.observation,
            CallbackCoverageStage.FORMAL,
            { scanned ->
                when (scanned) {
                    is Refinement.Refined -> Refinement.Refined(scanned.value.evidence)
                    is Refinement.Rejected -> scanned
                }
            },
        ) {
            when (val restored = reuse.restore(formal, body, prepared.binding)) {
                is Refinement.Rejected -> restored
                is Refinement.Refined ->
                    when (val value = restored.value) {
                        is CallbackSummaryRestore.Reused ->
                            Refinement.Refined(
                                CallbackSummaryScan(value.flow.snapshot(), CallbackSummaryCandidate.None)
                            )
                        CallbackSummaryRestore.Missing -> capture(formal)
                    }
            }
        }

    private fun capture(
        formal: CallbackParameterIdentity
    ): Refinement<CallbackSummaryScan, CallbackInvocationFlowFailure> {
        val evidence =
            when (val read = IntellijCallbackFlowScan(context, prepared, summaries).read(formal)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        return when (
            val captured =
                reuse.capture(
                    formal,
                    evidence.invocations,
                    evidence.obligations,
                    evidence.owners,
                    evidence.scan,
                    evidence.forwarding,
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(CallbackSummaryScan(evidence, captured.value))
            is Refinement.Rejected -> captured
        }
    }
}

private fun CallbackInvocationFlow.snapshot() =
    CallbackFormalScanSnapshot(invocations, obligations, ownerBindings, scan, forwarding)
