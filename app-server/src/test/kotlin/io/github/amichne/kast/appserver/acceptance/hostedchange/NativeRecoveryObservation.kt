package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class NativeRecoveryObservation {
    MANUAL_RECOVERY_REQUIRED,
    OTHER_RESULT,
}

internal fun NativeToolResult.recoveryObservation(): NativeRecoveryObservation =
    when (this) {
        is NativeToolResult.Document ->
            if (rejected()) NativeRecoveryObservation.OTHER_RESULT
            else if (
                payload["status"] == JsonPrimitive("qualified") &&
                    payload["state"] == JsonPrimitive("recovery-required") &&
                    payload["qualification"] == JsonPrimitive("manual-recovery-required")
            )
                NativeRecoveryObservation.MANUAL_RECOVERY_REQUIRED
            else NativeRecoveryObservation.OTHER_RESULT
        is NativeToolResult.BrokerRejected,
        NativeToolResult.ResponseLost -> NativeRecoveryObservation.OTHER_RESULT
    }

internal fun NativeChangeEvidence.expectRecoveryRequired(case: String, result: NativeToolResult) {
    val observed = result.recoveryObservation()
    if (observed != NativeRecoveryObservation.MANUAL_RECOVERY_REQUIRED) {
        record(
            case,
            NativeCaseOutcome.REJECTED,
            buildJsonObject {
                put("expectedRecovery", NativeRecoveryObservation.MANUAL_RECOVERY_REQUIRED.name)
                put("observedRecovery", observed.name)
            },
        )
        throw NativeRejected(NativeFailure.EXPECTED_REJECTION_MISSING)
    }
}
