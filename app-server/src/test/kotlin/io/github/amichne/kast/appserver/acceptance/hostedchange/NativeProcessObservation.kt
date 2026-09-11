package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import io.github.amichne.kast.appserver.provider.BrokerProcessFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
private enum class NativeOutputField(val wire: String) {
    OPERATION("operation"),
    BOUNDARY("boundary"),
    STATUS("status"),
    LIVE("live"),
    PLAN_IDENTITY("planIdentity"),
    RECEIPT_IDENTITY("receiptIdentity"),
    CHANGES("changes"),
    STATE("state"),
    REASON("reason"),
    QUALIFICATION("qualification"),
    FAILURE("failure"),
    OUTCOME("outcome"),
    TYPE("type"),
    ITEMS("items"),
    FAILURES("failures"),
    VERSION("version"),
    UNKNOWN("");

    companion object {
        fun observe(name: String): NativeOutputField = entries.firstOrNull { it.wire == name } ?: UNKNOWN
    }
}

@Serializable
private data class NativeRejectedProcessObservation(
    val outcome: String,
    val failure: BrokerProcessFailure,
)

@Serializable
private data class NativeCompletedProcessObservation(
    val outcome: NativeProcessOutcome,
    val shape: NativeProcessShape,
    val fields: List<NativeOutputField>,
    val boundaryRejection: NativeBoundaryRejection,
)

@Serializable
private enum class NativeProcessOutcome {
    @SerialName("zero-exit") ZERO_EXIT,
    @SerialName("nonzero-exit") NONZERO_EXIT,
}

@Serializable
private enum class NativeProcessShape {
    @SerialName("object") OBJECT,
    @SerialName("non-object") NON_OBJECT,
}

/** A structural observation never contains field values, executable references, diffs, or process messages. */
internal fun processObservation(result: BrokerProcessExecution): JsonObject =
    when (result) {
        is BrokerProcessExecution.Rejected ->
            Json.encodeToJsonElement(
                    NativeRejectedProcessObservation.serializer(),
                    NativeRejectedProcessObservation("process-rejected", result.failure),
                )
                .jsonObject
        is BrokerProcessExecution.Completed -> completedObservation(result)
    }

private fun completedObservation(result: BrokerProcessExecution.Completed): JsonObject {
    val raw = if (result.exitCode == 0) result.stdout else withoutOwnedJvmBanners(result.stderr)
    val document = processDocument(raw)
    return Json.encodeToJsonElement(
            NativeCompletedProcessObservation.serializer(),
            NativeCompletedProcessObservation(
                outcome =
                    if (result.exitCode == 0) NativeProcessOutcome.ZERO_EXIT else NativeProcessOutcome.NONZERO_EXIT,
                shape = if (document == null) NativeProcessShape.NON_OBJECT else NativeProcessShape.OBJECT,
                fields = document?.keys?.map(NativeOutputField::observe)?.distinct()?.sortedBy { it.name }.orEmpty(),
                boundaryRejection = nativeBoundaryRejection(result),
            ),
        )
        .jsonObject
}

private fun processDocument(raw: String): JsonObject? =
    try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

@Serializable
internal enum class NativeBoundaryRejection {
    NONE,
    IDE_HOST_UNAVAILABLE,
    IDE_OPERATION_UNSUPPORTED,
    IDE_CONFIGURATION_REJECTED,
    IDE_DESCRIPTOR_REJECTED,
    IDE_RESPONSE_REJECTED,
    IDE_REQUEST_TOO_LARGE,
    IDE_DEADLINE_EXCEEDED,
    IDE_TRANSPORT_REJECTED,
    IDE_SCHEMA_UNAVAILABLE,
    IDE_APPROVAL_REQUIRED,
    IDE_APPROVAL_REJECTED,
    UNCLASSIFIED,
}

internal fun nativeBoundaryRejection(result: BrokerProcessExecution): NativeBoundaryRejection {
    if (result !is BrokerProcessExecution.Completed || result.exitCode == 0) return NativeBoundaryRejection.NONE
    val raw = withoutOwnedJvmBanners(result.stderr)
    val document = processDocument(raw) ?: return NativeBoundaryRejection.UNCLASSIFIED
    if (document.keys != setOf("status", "boundary", "reason") || document["status"] != JsonPrimitive("rejected"))
        return NativeBoundaryRejection.UNCLASSIFIED
    return when (document["boundary"]) {
        JsonPrimitive("runtime") ->
            when (document["reason"]) {
                JsonPrimitive("ide-host-unavailable") -> NativeBoundaryRejection.IDE_HOST_UNAVAILABLE
                JsonPrimitive("ide-configuration-rejected") -> NativeBoundaryRejection.IDE_CONFIGURATION_REJECTED
                JsonPrimitive("ide-descriptor-rejected") -> NativeBoundaryRejection.IDE_DESCRIPTOR_REJECTED
                JsonPrimitive("ide-response-rejected") -> NativeBoundaryRejection.IDE_RESPONSE_REJECTED
                JsonPrimitive("ide-request-too-large") -> NativeBoundaryRejection.IDE_REQUEST_TOO_LARGE
                JsonPrimitive("ide-deadline-exceeded") -> NativeBoundaryRejection.IDE_DEADLINE_EXCEEDED
                JsonPrimitive("ide-transport-rejected") -> NativeBoundaryRejection.IDE_TRANSPORT_REJECTED
                JsonPrimitive("ide-schema-unavailable") -> NativeBoundaryRejection.IDE_SCHEMA_UNAVAILABLE
                JsonPrimitive("ide-approval-required") -> NativeBoundaryRejection.IDE_APPROVAL_REQUIRED
                JsonPrimitive("ide-approval-rejected") -> NativeBoundaryRejection.IDE_APPROVAL_REJECTED
                else -> NativeBoundaryRejection.UNCLASSIFIED
            }
        JsonPrimitive("usage") ->
            if (document["reason"] == JsonPrimitive("ide-operation-unsupported"))
                NativeBoundaryRejection.IDE_OPERATION_UNSUPPORTED
            else NativeBoundaryRejection.UNCLASSIFIED
        else -> NativeBoundaryRejection.UNCLASSIFIED
    }
}

private fun withoutOwnedJvmBanners(raw: String): String =
    raw.lineSequence()
        .filterNot { it.startsWith("Picked up JAVA_TOOL_OPTIONS:") || it.startsWith("Picked up _JAVA_OPTIONS:") }
        .joinToString("\n")
