package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import io.github.amichne.kast.appserver.provider.BrokerProcessExecutor
import io.github.amichne.kast.appserver.provider.BrokerProcessInput
import io.github.amichne.kast.appserver.provider.BrokerProcessRequest
import io.github.amichne.kast.appserver.provider.JdkBrokerProcessExecutor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal enum class NativeCaseOutcome {
    PASSED,
    REJECTED,
    UNQUALIFIED,
}

internal enum class NativeFailure {
    INPUT_REJECTED,
    PRODUCT_CLASS_ORIGIN_REJECTED,
    ARTIFACT_INCOMPATIBLE,
    PROVIDER_QUALIFICATION_REJECTED,
    CONTRACT_REJECTED,
    PROTOCOL_REJECTED,
    PROVIDER_REJECTED,
    RESULT_SHAPE_REJECTED,
    SOURCE_CHANGED,
    APPROVAL_PREVIEW_CHANGED,
    VERIFIED_RECEIPT_MISSING,
    DUPLICATE_DECLARATION,
    EXPECTED_REJECTION_MISSING,
    OWNER_DID_NOT_CHANGE,
    EPOCH_DID_NOT_CHANGE,
    RECOVERY_FAILED,
    CONTROL_REJECTED,
    PROBE_REJECTED,
    PSI_STRUCTURE_REJECTED,
    UNDO_REJECTED,
    TIMEOUT,
    INTERNAL_FAILURE,
}

internal class NativeRejected(val failure: NativeFailure) : RuntimeException(failure.name)

internal fun demand(condition: Boolean, failure: NativeFailure) {
    if (!condition) throw NativeRejected(failure)
}

internal fun <T, E> Refinement<T, E>.nativeValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw NativeRejected(NativeFailure.INPUT_REJECTED)
    }

internal fun <T, E> Validation<T, E>.nativeValue(): T =
    when (this) {
        is Validation.Validated -> value
        is Validation.Rejected -> throw NativeRejected(NativeFailure.CONTRACT_REJECTED)
    }

internal fun sha256(bytes: ByteArray): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun JsonObject.objectAt(name: String): JsonObject =
    this[name] as? JsonObject ?: throw NativeRejected(NativeFailure.RESULT_SHAPE_REJECTED)

internal fun JsonObject.textAt(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw NativeRejected(NativeFailure.RESULT_SHAPE_REJECTED)

internal fun privateWrite(path: Path, value: String) {
    if (!Files.exists(path))
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(path, value)
}

/** No subprocess is replaced. Only bounded route names/counts accompany the real process adapter. */
internal class NativeProcessTrace(private val privateDirectory: Path) : BrokerProcessExecutor {
    private val routes = CopyOnWriteArrayList<List<String>>()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    val completedEffects = Channel<Unit>(32)
    val plannedReferenceDigests = CopyOnWriteArrayList<String>()
    val boundaryRejections = CopyOnWriteArrayList<NativeBoundaryRejection>()

    fun isolatedStartupCount(): Int = routes.count { it.firstOrNull() in setOf("start", "prepare", "worker") }

    override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
        routes += request.arguments
        if (request.arguments == listOf("change", "plan")) {
            val input =
                request.input as? BrokerProcessInput.Document
                    ?: throw NativeRejected(NativeFailure.RESULT_SHAPE_REJECTED)
            val intent = Json.parseToJsonElement(input.value).jsonObject.objectAt("intent")
            if (intent["exactTarget"] != null) {
                plannedReferenceDigests += sha256(intent.textAt("exactTarget").toByteArray())
            }
        }
        val number = sequence.incrementAndGet()
        val result = JdkBrokerProcessExecutor.execute(request)
        boundaryRejections += nativeBoundaryRejection(result)
        privateWrite(privateDirectory.resolve("process-$number.private.json"), processObservation(result).toString())
        if (request.arguments.lastOrNull() == "--hosted-approved-invocation") completedEffects.send(Unit)
        return result
    }

    fun document(): JsonObject = buildJsonObject {
        put("processCount", routes.size)
        put("isolatedStartupCommandCount", routes.count { it.firstOrNull() in setOf("start", "prepare", "worker") })
        put("approvalPrepareCount", routes.count { it.lastOrNull() == "--hosted-approval-prepare" })
        put("approvedInvocationCount", routes.count { it.lastOrNull() == "--hosted-approved-invocation" })
    }
}

internal class NativeChangeEvidence(private val report: Path) {
    private val cases = linkedMapOf<String, JsonObject>()
    private var metadata = buildJsonObject {}

    fun metadata(value: JsonObject) {
        metadata = value
        persist()
    }

    fun record(name: String, outcome: NativeCaseOutcome, evidence: JsonObject = buildJsonObject {}) {
        cases[name] = buildJsonObject {
            put("outcome", outcome.name.lowercase())
            put("evidence", evidence)
        }
        persist()
        println(
            buildJsonObject {
                put("event", "case")
                put("case", name)
                put("outcome", outcome.name.lowercase())
            }
        )
        System.out.flush()
    }

    fun terminal(failure: NativeFailure?) {
        metadata =
            JsonObject(
                metadata +
                    ("status" to JsonPrimitive(if (failure == null) "observed" else "rejected")) +
                    ("failure" to (failure?.let { JsonPrimitive(it.name) } ?: JsonNull))
            )
        persist()
    }

    private fun persist() =
        privateWrite(
            report,
            buildJsonObject {
                put("schemaVersion", 1)
                put("metadata", metadata)
                put("cases", JsonObject(cases))
            }
                .toString(),
        )
}
