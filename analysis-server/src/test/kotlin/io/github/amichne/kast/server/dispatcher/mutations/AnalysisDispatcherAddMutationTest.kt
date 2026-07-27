package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherAddMutationTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `symbol add file dispatches file creation and diagnostics`() {
        FakeAnalysisBackend.sample(tempDir)
        val targetFile = tempDir.resolve("src").resolve("Added.kt")
        val contentFile = tempDir.resolve("added-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Added\n")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/add-file",
            params = json.encodeToJsonElement(
                KastAddFileRequest.serializer(),
                KastAddFileRequest(
                    workspaceRoot = tempDir.toString(),
                    filePath = targetFile.toString(),
                    contentFile = contentFile.toString(),
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.ADD_FILE, success.operation)
        assertEquals(true, success.applied)
        assertEquals(1, success.editCount)
        assertEquals(listOf(targetFile.toString()), success.createdFiles)
        assertEquals("package sample\n\nclass Added\n", targetFile.readText())
    }

    @Test
    fun `symbol add file refreshes semantic admission before optimization and diagnostics`() {
        val backend = RecordingMutationBackend(FakeAnalysisBackend.sample(tempDir))
        val targetFile = tempDir.resolve("src").resolve("Added.kt")
        val contentFile = tempDir.resolve("added-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Added\n")
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        runBlocking {
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

        assertEquals(
            listOf("apply", "refresh", "optimize", "diagnostics"),
            backend.operations,
        )
    }

    @Test
    fun `symbol add file fails closed when semantic admission remains incomplete`() {
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
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastScopeMutationResponse.serializer(), response.result)

        val success = result as KastScopeMutationSuccessResponse
        assertFalse(success.ok)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, success.diagnostics.semanticOutcome)
        assertEquals(1, success.diagnostics.requestedFileCount)
        assertEquals(0, success.diagnostics.analyzedFileCount)
        assertEquals(1, success.diagnostics.skippedFileCount)
        assertEquals(listOf("apply", "refresh"), backend.operations)
    }

    @Test
    fun `symbol add file preflights refresh capability before creating the file`() {
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

        assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code)
        assertEquals(0, backend.applyCalls)
        assertFalse(Files.exists(targetFile))
    }

    @Test
    fun `symbol add declaration dispatches file scope insertion`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("declaration-content.kt")
        Files.writeString(contentFile, "\nfun added() = Unit\n")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/add-declaration",
            params = json.encodeToJsonElement(
                KastAddDeclarationRequest.serializer(),
                KastAddDeclarationRequest(
                    workspaceRoot = tempDir.toString(),
                    placement = KastPlacementSelector(
                        scope = KastFilePlacementScope(targetFile.toString()),
                        anchor = KastAtPlacementAnchor(KastPlacementAnchor.FILE_BOTTOM),
                    ),
                    contentFile = contentFile.toString(),
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.ADD_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertEquals(targetFile.toString(), success.placement?.filePath)
        assertTrue(targetFile.readText().endsWith("\nfun added() = Unit\n"))
    }

    @Test
    fun `symbol add declaration after symbol uses declaration scope end`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("after-declaration-content.kt")
        Files.writeString(contentFile, "\nfun added() = Unit\n")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/add-declaration",
            params = json.encodeToJsonElement(
                KastAddDeclarationRequest.serializer(),
                KastAddDeclarationRequest(
                    workspaceRoot = tempDir.toString(),
                    placement = KastPlacementSelector(
                        scope = KastFilePlacementScope(targetFile.toString()),
                        anchor = KastAfterSymbolPlacementAnchor(
                            symbol = "greet",
                            fileHint = targetFile.toString(),
                            kind = WrapperNamedSymbolKind.FUNCTION,
                        ),
                    ),
                    contentFile = contentFile.toString(),
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.ADD_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertTrue(targetFile.readText().contains("fun greet() = \"hi\"\nfun added() = Unit\n"))
        assertFalse(targetFile.readText().contains("fun greet\nfun added()"))
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
