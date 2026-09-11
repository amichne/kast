package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

/** A structural observation never contains field values, executable references, diffs, or process messages. */
internal fun processObservation(result: BrokerProcessExecution): JsonObject =
    when (result) {
        is BrokerProcessExecution.Rejected ->
            buildJsonObject {
                put("outcome", "process-rejected")
                put("failure", result.failure.name)
            }
        is BrokerProcessExecution.Completed -> completedObservation(result)
    }

private fun completedObservation(result: BrokerProcessExecution.Completed): JsonObject = buildJsonObject {
    put("outcome", if (result.exitCode == 0) "zero-exit" else "nonzero-exit")
    val document = processDocument(if (result.exitCode == 0) result.stdout else result.stderr)
    put("shape", if (document == null) "non-object" else "object")
    put("fields", observedFields(document))
    put("boundaryRejection", nativeBoundaryRejection(result).name)
}

private fun processDocument(raw: String): JsonObject? =
    try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun observedFields(document: JsonObject?) = buildJsonArray {
    document
        ?.keys
        ?.map(NativeOutputField::observe)
        ?.distinct()
        ?.sortedBy { it.name }
        ?.forEach { field ->
            add(JsonPrimitive(field.name))
        }
}

internal enum class NativeBoundaryRejection {
    NONE,
    IDE_HOST_UNAVAILABLE,
    IDE_OPERATION_UNSUPPORTED,
    UNCLASSIFIED,
}

internal fun nativeBoundaryRejection(result: BrokerProcessExecution): NativeBoundaryRejection {
    if (result !is BrokerProcessExecution.Completed || result.exitCode == 0) return NativeBoundaryRejection.NONE
    val raw =
        result.stderr
            .lineSequence()
            .filterNot {
                it.startsWith("Picked up JAVA_TOOL_OPTIONS:") || it.startsWith("Picked up _JAVA_OPTIONS:")
            }
            .joinToString("\n")
    val document = processDocument(raw) ?: return NativeBoundaryRejection.UNCLASSIFIED
    if (document.keys != setOf("status", "boundary", "reason") || document["status"] != JsonPrimitive("rejected"))
        return NativeBoundaryRejection.UNCLASSIFIED
    return when {
        document["boundary"] == JsonPrimitive("runtime") &&
            document["reason"] == JsonPrimitive("ide-host-unavailable") -> NativeBoundaryRejection.IDE_HOST_UNAVAILABLE
        document["boundary"] == JsonPrimitive("usage") &&
            document["reason"] == JsonPrimitive("ide-operation-unsupported") ->
            NativeBoundaryRejection.IDE_OPERATION_UNSUPPORTED
        else -> NativeBoundaryRejection.UNCLASSIFIED
    }
}
