package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Scripted envelopes are checked against the selected real Codex schemas before the native workflow starts. */
internal object NativeControllerProtocol {
    fun contracts(schemas: Path, workspace: Path): CodexProtocolContracts {
        val schemaFiles = Files.walk(schemas).use { paths -> paths.filter(Files::isRegularFile).toList() }
        val contracts =
            CodexProtocolContracts.define(
                    CodexOwnedSchema.entries.associateWith { schema ->
                        val file =
                            schemaFiles.singleOrNull { it.fileName.toString() == schema.fileName }
                                ?: nativeSchemaFileRejected(schema)
                        Json.parseToJsonElement(Files.readString(file)).jsonObject
                    }
                )
                .nativeContracts()
        contracts
            .admit(CodexOwnedSchema.INITIALIZE_PARAMS, initialize().objectAt("params"))
            .nativeEnvelope(NativeContractStage.INITIALIZE, CodexOwnedSchema.INITIALIZE_PARAMS)
        contracts
            .admit(CodexOwnedSchema.THREAD_START_PARAMS, threadStart(workspace).objectAt("params"))
            .nativeEnvelope(NativeContractStage.THREAD_START, CodexOwnedSchema.THREAD_START_PARAMS)
        contracts
            .admit(CodexOwnedSchema.THREAD_START_RESPONSE, threadStarted(workspace, "native-probe").objectAt("result"))
            .nativeEnvelope(NativeContractStage.THREAD_STARTED, CodexOwnedSchema.THREAD_START_RESPONSE)
        return contracts
    }

    fun initialize(): JsonObject = buildJsonObject {
        put("id", 0)
        put("method", "initialize")
        putJsonObject("params") {
            putJsonObject("clientInfo") {
                put("name", "hosted-change-acceptance")
                put("version", "1")
            }
        }
    }

    fun threadStart(workspace: Path): JsonObject = buildJsonObject {
        put("id", 1)
        put("method", "thread/start")
        putJsonObject("params") { put("cwd", workspace.toString()) }
    }

    fun threadStarted(workspace: Path, thread: String): JsonObject = buildJsonObject {
        put("id", 1)
        putJsonObject("result") {
            put("cwd", workspace.toString())
            put("approvalPolicy", "never")
            put("approvalsReviewer", "user")
            put("model", "native-protocol-fixture")
            put("modelProvider", "fixture")
            putJsonObject("sandbox") { put("type", "dangerFullAccess") }
            put("thread", thread(workspace, thread))
        }
    }

    private fun thread(workspace: Path, thread: String): JsonObject = buildJsonObject {
        put("id", thread)
        putJsonArray("turns") {}
        put("cliVersion", "0")
        put("createdAt", 0)
        put("cwd", workspace.toString())
        put("ephemeral", false)
        put("modelProvider", "fixture")
        put("preview", "Native hosted change acceptance")
        put("projectId", JsonNull)
        put("sessionId", thread)
        put("source", "appServer")
        putJsonObject("status") { put("type", "idle") }
        put("updatedAt", 0)
    }
}
