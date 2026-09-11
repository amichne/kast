package io.github.amichne.kast.fixtureprobe

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val PROBE_VERSION = 1
internal const val MAXIMUM_REQUEST_BYTES = 4096
internal const val MAXIMUM_SOURCE_BYTES = 262144
internal const val MAXIMUM_RESPONSE_BYTES = 32768
internal const val MAXIMUM_DECLARATIONS = 64
internal const val PRODUCTION_COMMAND = "Kast semantic change"
internal const val FIXTURE_COMMAND = "Kast native fixture setup"
internal const val DIRTY_MARKER = "\n// kast-native-fixture-unsaved-probe\n"

internal sealed interface ProbeResult<out Value> {
    data class Accepted<Value>(val value: Value) : ProbeResult<Value>

    data class Rejected(val failure: ProbeFailure) : ProbeResult<Nothing>
}

internal enum class ProbeFailure {
    NOT_ENABLED,
    SANDBOX_REJECTED,
    PROJECT_MISMATCH,
    SPOOL_REJECTED,
    REQUEST_TOO_LARGE,
    MALFORMED_REQUEST,
    REQUEST_ID_MISMATCH,
    UNKNOWN_COMMAND,
    IMAGE_GUARD_REQUIRED,
    TARGET_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    SOURCE_ENCODING_REJECTED,
    SAVED_IMAGE_CHANGED,
    DOCUMENT_IMAGE_CHANGED,
    DOCUMENT_STATE_REJECTED,
    PSI_UNAVAILABLE,
    PSI_LIMIT_EXCEEDED,
    UNDO_UNAVAILABLE,
    UNDO_COMMAND_MISMATCH,
    UNDO_CONFIRMATION_REQUIRED,
    UNDO_IMAGE_MISMATCH,
    SAVE_REJECTED,
    NATIVE_UNAVAILABLE,
    RESPONSE_UNAVAILABLE,
    BARRIER_ALREADY_ARMED,
    BARRIER_IMAGE_MISMATCH,
    PLUGIN_UNAVAILABLE,
    PLUGIN_UNLOAD_UNSUPPORTED,
    PLUGIN_UNLOAD_REJECTED,
}

internal enum class ProbeCommand {
    OBSERVE,
    DIRTY_UNCOMMITTED,
    COMMIT_DOCUMENT,
    RESTORE_SAVED,
    UNDO_PRODUCTION_CHANGE,
    ARM_POST_SAVE_BARRIER,
    UNLOAD_PRODUCTION_PLUGIN,
}

@JvmInline
internal value class ProbeDigest private constructor(val value: String) {
    companion object {
        fun parse(value: String): ProbeResult<ProbeDigest> =
            if (value.matches(Regex("[0-9a-f]{64}"))) ProbeResult.Accepted(ProbeDigest(value))
            else ProbeResult.Rejected(ProbeFailure.MALFORMED_REQUEST)

        fun observe(bytes: ByteArray): ProbeDigest =
            ProbeDigest(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            )
    }
}

internal sealed interface ProbeExpectedImages {
    val preimage: ProbeDigest
    val currentSaved: ProbeDigest

    data class Unchanged(override val preimage: ProbeDigest) : ProbeExpectedImages {
        override val currentSaved: ProbeDigest
            get() = preimage
    }

    data class Changed(override val preimage: ProbeDigest, val postimage: ProbeDigest) : ProbeExpectedImages {
        override val currentSaved: ProbeDigest
            get() = postimage
    }
}

internal class ProbeRequest
private constructor(
    val id: UUID,
    val command: ProbeCommand,
    val images: ProbeExpectedImages,
) {
    val expectedCurrentSaved: ProbeDigest
        get() = if (command == ProbeCommand.ARM_POST_SAVE_BARRIER) images.preimage else images.currentSaved

    fun encode(): String = buildJsonObject {
        put("version", PROBE_VERSION)
        put("id", id.toString())
        put("command", command.name)
        put("expectedPreimageSha256", images.preimage.value)
        when (val retained = images) {
            is ProbeExpectedImages.Unchanged -> Unit
            is ProbeExpectedImages.Changed -> put("expectedPostimageSha256", retained.postimage.value)
        }
    }
        .toString()

    companion object {
        fun decode(bytes: ByteArray, fileId: UUID): ProbeResult<ProbeRequest> {
            if (bytes.size > MAXIMUM_REQUEST_BYTES) return ProbeResult.Rejected(ProbeFailure.REQUEST_TOO_LARGE)
            val raw = bytes.toString(StandardCharsets.UTF_8)
            val body =
                try {
                    Json.parseToJsonElement(raw) as? JsonObject
                        ?: return ProbeResult.Rejected(ProbeFailure.MALFORMED_REQUEST)
                } catch (_: SerializationException) {
                    return ProbeResult.Rejected(ProbeFailure.MALFORMED_REQUEST)
                }
            if ((body["version"] as? JsonPrimitive)?.intOrNull != PROBE_VERSION) {
                return ProbeResult.Rejected(ProbeFailure.MALFORMED_REQUEST)
            }
            val id =
                try {
                    UUID.fromString(body.string("id"))
                } catch (_: IllegalArgumentException) {
                    return ProbeResult.Rejected(ProbeFailure.MALFORMED_REQUEST)
                }
            if (id != fileId) return ProbeResult.Rejected(ProbeFailure.REQUEST_ID_MISMATCH)
            val command =
                ProbeCommand.entries.singleOrNull { it.name == body.string("command") }
                    ?: return ProbeResult.Rejected(ProbeFailure.UNKNOWN_COMMAND)
            val images =
                when (val parsed = decodeImages(body, command)) {
                    is ProbeResult.Accepted -> parsed.value
                    is ProbeResult.Rejected -> return parsed
                }
            val request = ProbeRequest(id, command, images)
            // Exact canonical encoding rejects duplicate/unknown keys, coercions, and alternate id encodings.
            return if (request.encode() == raw) ProbeResult.Accepted(request)
            else ProbeResult.Rejected(ProbeFailure.MALFORMED_REQUEST)
        }

        private fun decodeImages(body: JsonObject, command: ProbeCommand): ProbeResult<ProbeExpectedImages> {
            val preimage =
                when (val parsed = ProbeDigest.parse(body.string("expectedPreimageSha256"))) {
                    is ProbeResult.Accepted -> parsed.value
                    is ProbeResult.Rejected -> return parsed
                }
            if ("expectedPostimageSha256" !in body) {
                return if (
                    (command == ProbeCommand.UNDO_PRODUCTION_CHANGE || command == ProbeCommand.ARM_POST_SAVE_BARRIER)
                )
                    ProbeResult.Rejected(ProbeFailure.IMAGE_GUARD_REQUIRED)
                else ProbeResult.Accepted(ProbeExpectedImages.Unchanged(preimage))
            }
            val postimage =
                when (val parsed = ProbeDigest.parse(body.string("expectedPostimageSha256"))) {
                    is ProbeResult.Accepted -> parsed.value
                    is ProbeResult.Rejected -> return parsed
                }
            if (preimage == postimage) return ProbeResult.Rejected(ProbeFailure.IMAGE_GUARD_REQUIRED)
            return ProbeResult.Accepted(ProbeExpectedImages.Changed(preimage, postimage))
        }
    }
}

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

internal enum class ProbeDocumentState {
    SAVED_COMMITTED,
    SAVED_UNCOMMITTED,
    DIRTY_COMMITTED,
    DIRTY_UNCOMMITTED;

    companion object {
        fun observe(unsaved: Boolean, committed: Boolean): ProbeDocumentState =
            when {
                unsaved && committed -> DIRTY_COMMITTED
                unsaved -> DIRTY_UNCOMMITTED
                committed -> SAVED_COMMITTED
                else -> SAVED_UNCOMMITTED
            }
    }
}

internal enum class ProbeSyntaxState {
    CLEAN,
    ERRORS,
    UNCOMMITTED,
}

internal enum class ProbeUndoState {
    PRODUCTION_CHANGE,
    OTHER,
    UNAVAILABLE,
}

internal enum class ProbeDeclarationKind {
    CLASS,
    OBJECT,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
    OTHER,
}

internal data class ProbeDeclaration(val name: String, val container: String, val kind: ProbeDeclarationKind)

internal data class ProbeEvidence(
    val saved: ProbeDigest,
    val document: ProbeDigest,
    val documentState: ProbeDocumentState,
    val syntax: ProbeSyntaxState,
    val undo: ProbeUndoState,
    val declarations: List<ProbeDeclaration>,
)

internal sealed interface ProbeExecution {
    data class Completed(val evidence: ProbeEvidence) : ProbeExecution

    data class BarrierArmed(val evidence: ProbeEvidence, val barrierId: UUID) : ProbeExecution

    data class LifecycleCompleted(val evidence: ProbeEvidence, val lifecycle: ProbePluginLifecycle) : ProbeExecution

    data class Rejected(val failure: ProbeFailure) : ProbeExecution

    data class EffectUncertain(val failure: ProbeFailure) : ProbeExecution
}

internal enum class ProbePluginLifecycle {
    UNLOADED
}
