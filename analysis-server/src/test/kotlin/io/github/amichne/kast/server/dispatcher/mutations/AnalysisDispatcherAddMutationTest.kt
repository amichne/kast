package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AnalysisDispatcherAddMutationTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `symbol add file is retired before file creation`() {
        FakeAnalysisBackend.sample(tempDir)
        val targetFile = tempDir.resolve("src").resolve("Added.kt")
        val contentFile = tempDir.resolve("added-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Added\n")

        val raw = runBlocking {
            dispatcher().dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals(-32601, error.error.code)
        assertFalse(Files.exists(targetFile))
    }

    @Test
    fun `symbol add file is retired before refresh optimization and diagnostics`() {
        val backend = RecordingMutationBackend(FakeAnalysisBackend.sample(tempDir))
        val targetFile = tempDir.resolve("src").resolve("Added.kt")
        val contentFile = tempDir.resolve("added-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Added\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals(-32601, error.error.code)
        assertEquals(emptyList<String>(), backend.operations)
    }

    @Test
    fun `symbol add file is retired before incomplete semantic admission`() {
        val backend = RecordingMutationBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            incompleteRefresh = true,
        )
        val targetFile = tempDir.resolve("src").resolve("Pending.kt")
        val contentFile = tempDir.resolve("pending-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Pending\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals(-32601, error.error.code)
        assertEquals(emptyList<String>(), backend.operations)
        assertFalse(Files.exists(targetFile))
    }

    @Test
    fun `symbol add file is retired before refresh capability preflight`() {
        val backend = MissingRefreshCapabilityBackend(FakeAnalysisBackend.sample(tempDir))
        val targetFile = tempDir.resolve("src").resolve("NoRefresh.kt")
        val contentFile = tempDir.resolve("no-refresh-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass NoRefresh\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/add-file",
                    params = json.encodeToJsonElement(
                        KastAddFileRequest.serializer(),
                        KastAddFileRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = targetFile.toString(),
                            contentFile = contentFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals(-32601, error.error.code)
        assertEquals(0, backend.applyCalls)
        assertFalse(Files.exists(targetFile))
    }

    @Test
    fun `scope mutation request interfaces do not change wire payloads`() {
        val request = KastAddStatementRequest(
            workspaceRoot = tempDir.toString(),
            insideScope = "sample.greet",
            anchor = KastStatementPlacementAnchor.BODY_END,
            contentFile = tempDir.resolve("statement.kt").toString(),
        )

        val payload = json.encodeToJsonElement(KastAddStatementRequest.serializer(), request).jsonObject

        assertEquals(KastScopeMutationOperation.ADD_STATEMENT, request.operation)
        assertEquals(NormalizedPath.ofAbsolute(tempDir), request.requestedWorkspaceRoot)
        assertEquals(NonBlankString("sample.greet"), request.requestedInsideScope)
        assertEquals(NormalizedPath.ofAbsolute(tempDir.resolve("statement.kt")), request.contentFilePath)
        assertFalse(payload.containsKey("operation"))
        assertFalse(payload.containsKey("requestedWorkspaceRoot"))
        assertFalse(payload.containsKey("requestedInsideScope"))
        assertFalse(payload.containsKey("contentFilePath"))
        assertEquals(JsonPrimitive("body-end"), payload["anchor"])
    }
}
