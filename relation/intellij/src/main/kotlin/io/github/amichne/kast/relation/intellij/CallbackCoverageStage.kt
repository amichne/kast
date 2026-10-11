package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase

/** These stages qualify detached callback evidence, never cache eligibility or final static graph admission. */
internal enum class CallbackCoverageStage(
    val phase: IntellijReadPhase,
    val complete: IntellijReadCounter,
    val incomplete: IntellijReadCounter,
) {
    FORMAL(
        IntellijReadPhase.CALLBACK_FORMAL_COVERAGE,
        IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
        IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_INCOMPLETE,
    ),
    SUPPLIER(
        IntellijReadPhase.CALLBACK_SUPPLIER_COVERAGE,
        IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_COMPLETE,
        IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
    ),
}

/** Record entered-but-incomplete attempts on cancellation or native failure, without containing either failure. */
internal fun <Value> observeCallbackCoverage(
    observation: IntellijReadObservation,
    stage: CallbackCoverageStage,
    evidence: (Value) -> Refinement<CallbackFormalScanSnapshot, CallbackInvocationFlowFailure>,
    read: () -> Value,
): Value {
    observation.phase(stage.phase)
    var outcome = stage.incomplete
    try {
        val result = read()
        when (val captured = evidence(result)) {
            is Refinement.Rejected -> Unit
            is Refinement.Refined -> {
                val snapshot = captured.value
                val unresolved =
                    when (stage) {
                        CallbackCoverageStage.FORMAL ->
                            snapshot.obligations.filter { it != CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }
                        CallbackCoverageStage.SUPPLIER -> snapshot.obligations.toList()
                    }
                if (snapshot.scan == CallbackInvocationScan.EXHAUSTIVE && unresolved.isEmpty()) outcome = stage.complete
                unresolved.forEach { observation.terminated(it.supplierTermination()) }
            }
        }
        return result
    } finally {
        observation.count(outcome)
    }
}

/** Retain the supplier's final invocation qualification at the observation boundary. */
internal fun observeCallbackSupplierCoverage(
    observation: IntellijReadObservation,
    read: () -> CallbackFormalScanSnapshot,
): CallbackFormalScanSnapshot {
    return observeCallbackCoverage(observation, CallbackCoverageStage.SUPPLIER, { Refinement.Refined(it) }) {
        val evidence = read()
        if (evidence.invocations.isEmpty() && evidence.scan == CallbackInvocationScan.INCOMPLETE)
            evidence.withSupplier(setOf(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN))
        else evidence
    }
}
