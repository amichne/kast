package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryDiagnosticReadIdentity
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation

/** Original diagnostic ownership survives preview fitting and later presentation without gaining live authority. */
internal fun HostedQueryOutcome.withQueryDiagnosticIdentity(trace: HostedReadTraceObservation): HostedQueryOutcome {
    val rejected = this as? OperationOutcome.Rejected ?: return this
    val completion = rejected.reason.reason() as? QueryRunRejection.CompletionUnproven ?: return this
    if (completion.diagnosticReadId != null) return this
    val identity =
        when (trace) {
            HostedReadTraceObservation.Unobserved -> return this
            is HostedReadTraceObservation.Observed -> QueryDiagnosticReadIdentity.fromBoundary(trace.identity.value)
        }
    val reason = completion.copy(diagnosticReadId = identity)
    return OperationOutcome.Rejected(
        when (val original = rejected.reason) {
            is AdmittedQueryRunRejection -> original.copy(reason = reason)
            is QueryRunRejection -> reason
        }
    )
}
