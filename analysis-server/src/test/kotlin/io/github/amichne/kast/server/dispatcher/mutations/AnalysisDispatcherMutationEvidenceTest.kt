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

class AnalysisDispatcherMutationEvidenceTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `raw diagnostics preserves incomplete semantic evidence`() {
        val file = sampleFile()
        val backend = IncompleteDiagnosticsBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "raw/diagnostics",
                    params = json.encodeToJsonElement(
                        DiagnosticsQuery.serializer(),
                        DiagnosticsQuery(filePaths = listOf(file.toString())),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(DiagnosticsResult.serializer(), response.result)

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.BACKEND_FAILURE, result.fileStatuses.single().state)
        assertEquals(1, result.requestedFileCount)
        assertEquals(0, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
    }

    @Test
    fun `mutation summary fails closed when post edit analysis is incomplete`() {
        val file = sampleFile()
        val backend = IncompleteDiagnosticsBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/write-and-validate",
                    params = json.encodeToJsonElement(
                        KastWriteAndValidateRequest.serializer(),
                        KastWriteAndValidateInsertAtOffsetRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = file.toString(),
                            offset = file.readText().length,
                            content = "\nfun added() = Unit\n",
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastWriteAndValidateResponse.serializer(), response.result)

        val success = result as KastWriteAndValidateSuccessResponse
        assertFalse(success.ok)
        assertFalse(success.diagnostics.clean)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, success.diagnostics.semanticOutcome)
        assertEquals(1, success.diagnostics.requestedFileCount)
        assertEquals(0, success.diagnostics.analyzedFileCount)
        assertEquals(1, success.diagnostics.skippedFileCount)
    }

    @Test
    fun `mutation summary remains dirty when an error is beyond the returned diagnostic limit`() {
        val file = sampleFile()
        val backend = CompilerDiagnosticsBeyondLimitBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend,
            config = AnalysisServerConfig(maxResults = 1),
        )
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/write-and-validate",
                    params = json.encodeToJsonElement(
                        KastWriteAndValidateRequest.serializer(),
                        KastWriteAndValidateInsertAtOffsetRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = file.toString(),
                            offset = file.readText().length,
                            content = "\nfun added() = Unit\n",
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastWriteAndValidateResponse.serializer(), response.result)

        val success = result as KastWriteAndValidateSuccessResponse
        assertFalse(success.ok)
        assertFalse(success.diagnostics.clean)
        assertEquals(1, success.diagnostics.errorCount)
        assertEquals(1, success.diagnostics.warningCount)
        assertEquals(listOf("LATE_COMPILER_ERROR"), success.diagnostics.errors.map(Diagnostic::code))
    }
}
