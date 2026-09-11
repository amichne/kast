package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class NativeProbeCommand {
    OBSERVE,
    DIRTY_UNCOMMITTED,
    COMMIT_DOCUMENT,
    RESTORE_SAVED,
    UNDO_PRODUCTION_CHANGE,
}

@Serializable
internal enum class NativeDocumentState {
    SAVED_COMMITTED,
    SAVED_UNCOMMITTED,
    DIRTY_COMMITTED,
    DIRTY_UNCOMMITTED,
}

@Serializable
internal enum class NativeSyntax {
    CLEAN,
    ERRORS,
    UNCOMMITTED,
}

@Serializable
internal enum class NativeUndo {
    PRODUCTION_CHANGE,
    OTHER,
    UNAVAILABLE,
}

@Serializable
internal enum class NativeDeclarationKind {
    CLASS,
    OBJECT,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
    OTHER,
}

@Serializable
internal data class NativeDeclaration(val name: String, val container: String, val kind: NativeDeclarationKind)

@Serializable
internal data class NativeProbeEvidence(
    val savedSha256: String,
    val documentSha256: String,
    val documentState: NativeDocumentState,
    val syntax: NativeSyntax,
    val undo: NativeUndo,
    val declarations: List<NativeDeclaration>,
) {
    fun summary(): JsonObject = buildJsonObject {
        put("savedSha256", savedSha256)
        put("documentSha256", documentSha256)
        put("documentState", documentState.name)
        put("syntax", syntax.name)
        put("undo", undo.name)
        put("declarationCount", declarations.size)
    }

    fun functionCount(name: String, container: String): Int = declarations.count {
        it.name == name && it.container == container && it.kind == NativeDeclarationKind.FUNCTION
    }
}

/** The separate probe changes only test-owned fixture state; production admission still decides every request. */
internal class NativeFixtureControls(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun restart() {
        demand(
            exchange("""{"event":"control","action":"restart-ide"}""") == "restart-completed",
            NativeFailure.CONTROL_REJECTED,
        )
    }

    suspend fun probe(command: NativeProbeCommand, preimage: String, postimage: String? = null): NativeProbeEvidence {
        demand(
            preimage.matches(DIGEST) && (postimage == null || postimage.matches(DIGEST)),
            NativeFailure.CONTROL_REJECTED,
        )
        val request = buildJsonObject {
            put("event", "control")
            put("action", "probe")
            put("command", command.name)
            put("preimageSha256", preimage)
            postimage?.let { put("postimageSha256", it) }
        }
        return admitProbeResponse(
            Json.parseToJsonElement(exchange(request.toString())) as? JsonObject
                ?: throw NativeRejected(NativeFailure.CONTROL_REJECTED),
            command,
        )
    }

    suspend fun interruptAfterSave(preimage: String, postimage: String, barrierId: String) {
        val request = buildJsonObject {
            put("event", "control")
            put("action", "wait-save-barrier-restart")
            put("barrierId", barrierId)
            put("preimageSha256", preimage)
            put("postimageSha256", postimage)
        }
        demand(exchange(request.toString()) == "barrier-restart-completed", NativeFailure.CONTROL_REJECTED)
    }

    suspend fun armSaveBarrier(preimage: String, postimage: String): String {
        val response = controlProbe("ARM_POST_SAVE_BARRIER", preimage, postimage)
        demand(
            response.textAt("outcome") == "BARRIER_ARMED" && response.textAt("id") == response.textAt("barrierId"),
            NativeFailure.PROBE_REJECTED,
        )
        return response.textAt("barrierId")
    }

    suspend fun unloadProduction(preimage: String) {
        val response = controlProbe("UNLOAD_PRODUCTION_PLUGIN", preimage)
        demand(
            response.textAt("outcome") == "LIFECYCLE_COMPLETED" && response.textAt("lifecycle") == "UNLOADED",
            NativeFailure.PROBE_REJECTED,
        )
    }

    private suspend fun controlProbe(command: String, preimage: String, postimage: String? = null): JsonObject {
        val request = buildJsonObject {
            put("event", "control")
            put("action", "probe")
            put("command", command)
            put("preimageSha256", preimage)
            postimage?.let { put("postimageSha256", it) }
        }
        return Json.parseToJsonElement(exchange(request.toString())) as? JsonObject
            ?: throw NativeRejected(NativeFailure.CONTROL_REJECTED)
    }

    suspend fun replaceBroker(planIdentity: String, sourceSha256: String): JsonObject {
        val request = buildJsonObject {
            put("event", "control")
            put("action", "replace-broker")
            put("planIdentity", planIdentity)
            put("sourceSha256", sourceSha256)
        }
        val response =
            Json.parseToJsonElement(exchange(request.toString())) as? JsonObject
                ?: throw NativeRejected(NativeFailure.CONTROL_REJECTED)
        demand(response.textAt("outcome") == "PASSED", NativeFailure.CONTROL_REJECTED)
        return response.objectAt("evidence")
    }

    private suspend fun exchange(request: String): String {
        println(request)
        System.out.flush()
        return withContext(ioDispatcher) { readlnOrNull() } ?: throw NativeRejected(NativeFailure.CONTROL_REJECTED)
    }

    companion object {
        private val DIGEST = Regex("[0-9a-f]{64}")

        internal fun admitProbeResponse(response: JsonObject, command: NativeProbeCommand): NativeProbeEvidence {
            demand(
                response.textAt("command") == command.name && response.textAt("outcome") == "COMPLETED",
                NativeFailure.PROBE_REJECTED,
            )
            demand(
                response.keys == setOf("version", "id", "command", "outcome", "evidence") &&
                    response["version"] == JsonPrimitive(1) &&
                    response
                        .textAt("id")
                        .matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
                NativeFailure.CONTROL_REJECTED,
            )
            val evidence = Json.decodeFromJsonElement(NativeProbeEvidence.serializer(), response.objectAt("evidence"))
            demand(
                evidence.savedSha256.matches(DIGEST) &&
                    evidence.documentSha256.matches(DIGEST) &&
                    evidence.declarations.size <= 64,
                NativeFailure.CONTROL_REJECTED,
            )
            return evidence
        }
    }
}
