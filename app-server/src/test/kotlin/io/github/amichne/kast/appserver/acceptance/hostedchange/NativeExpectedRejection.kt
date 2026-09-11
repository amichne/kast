package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class NativeExpectedRejection {
    BROKER_INVALID_ARGUMENTS,
    APPROVAL_DECLINED,
    APPROVAL_CANCELLED,
    APPROVAL_CONTROLLER_REJECTED,
    EXACT_SYMBOL_REQUIRED,
    WORKSPACE_NOT_READY,
    CONTENT_CHANGED,
    OTHER_REJECTION,
    NOT_REJECTED,
    RESPONSE_LOST,
}

internal fun NativeChangeEvidence.expectRejection(
    case: String,
    expected: NativeExpectedRejection,
    result: NativeToolResult,
) {
    val observed = result.rejectionObservation()
    if (observed != expected) {
        record(
            case,
            NativeCaseOutcome.REJECTED,
            buildJsonObject {
                put("expectedRejection", expected.name)
                put("observedRejection", observed.name)
            },
        )
        throw NativeRejected(NativeFailure.EXPECTED_REJECTION_MISSING)
    }
}

internal fun NativeToolResult.rejectionObservation(): NativeExpectedRejection =
    when (this) {
        is NativeToolResult.BrokerRejected ->
            when (failure) {
                NativeBrokerRejection.INVALID_ARGUMENTS -> NativeExpectedRejection.BROKER_INVALID_ARGUMENTS
                NativeBrokerRejection.PLAN_APPROVAL_DECLINED -> NativeExpectedRejection.APPROVAL_DECLINED
                NativeBrokerRejection.PLAN_APPROVAL_CANCELLED -> NativeExpectedRejection.APPROVAL_CANCELLED
                NativeBrokerRejection.PLAN_APPROVAL_CONTROLLER_REJECTED ->
                    NativeExpectedRejection.APPROVAL_CONTROLLER_REJECTED
                NativeBrokerRejection.OTHER -> NativeExpectedRejection.OTHER_REJECTION
            }
        is NativeToolResult.Document ->
            when {
                !rejected() -> NativeExpectedRejection.NOT_REJECTED
                payload["reason"] == JsonPrimitive("exact-symbol-required") ->
                    NativeExpectedRejection.EXACT_SYMBOL_REQUIRED
                payload["reason"] == JsonPrimitive("workspace-not-ready") -> NativeExpectedRejection.WORKSPACE_NOT_READY
                payload["reason"] == JsonPrimitive("content-changed") -> NativeExpectedRejection.CONTENT_CHANGED
                else -> NativeExpectedRejection.OTHER_REJECTION
            }
        NativeToolResult.ResponseLost -> NativeExpectedRejection.RESPONSE_LOST
    }
