package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastMutationEditApplicationState
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSnapshot
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationState
import io.github.amichne.kast.api.contract.mutation.KastMutationProgressStage
import io.github.amichne.kast.api.contract.mutation.KastMutationSubmissionReceipt
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.skill.KastAddDeclarationRequest
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.contract.skill.KastAtPlacementAnchor
import io.github.amichne.kast.api.contract.skill.KastFilePlacementScope
import io.github.amichne.kast.api.contract.skill.KastPlacementAnchor
import io.github.amichne.kast.api.contract.skill.KastPlacementSelector
import io.github.amichne.kast.api.contract.skill.KastRenameBySymbolRequest
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import io.github.amichne.kast.api.validation.ParsedDiagnosticsQuery
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RpcAnalysisDispatcher
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class MutationOperationLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `retry applies once and terminal status retains all progress and result`() = runBlocking {
        val backend = FakeAnalysisBackend.sample(tempDir)
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig())
        val target = tempDir.resolve("src/Sample.kt")
        val contentFile = tempDir.resolve("declaration.kt")
        Files.writeString(contentFile, "\nfun added() = Unit\n")
        val mutation = KastSemanticMutation.AddDeclaration(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-lifecycle"),
            request = KastAddDeclarationRequest(
                workspaceRoot = tempDir.toString(),
                placement = KastPlacementSelector(
                    scope = KastFilePlacementScope(target.toString()),
                    anchor = KastAtPlacementAnchor(KastPlacementAnchor.FILE_BOTTOM),
                ),
                contentFile = contentFile.toString(),
            ),
        )

        val first = submit(dispatcher, mutation)
        val retry = submit(dispatcher, mutation)
        val terminal = awaitTerminal(
            dispatcher,
            KastMutationOperationSelector.ByOperationId(first.operation.operationId),
        )

        assertEquals(first.operation.operationId, retry.operation.operationId)
        assertTrue(retry.deduplicated)
        assertEquals(1, target.readText().split("fun added() = Unit").size - 1)
        assertEquals(
            listOf(
                KastMutationProgressStage.IDENTITY_RESOLUTION,
                KastMutationProgressStage.EDIT_APPLICATION,
                KastMutationProgressStage.WORKSPACE_REFRESH,
                KastMutationProgressStage.IMPORT_OPTIMIZATION,
                KastMutationProgressStage.DIAGNOSTICS,
            ),
            terminal.state.trace.enteredStages,
        )
        val completed = terminal.state as KastMutationOperationState.Completed
        assertTrue(completed.result is KastSemanticMutationResult.Scope)
        assertEquals(
            terminal,
            status(dispatcher, KastMutationOperationSelector.ByIdempotencyKey(mutation.idempotencyKey)),
        )
    }

    @Test
    fun `operation longer than ten seconds returns receipt before terminal result`() = runBlocking {
        val backend = DelayedApplyBackend(FakeAnalysisBackend.sample(tempDir), 10_100)
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig(requestTimeoutMillis = 500))
        val contentFile = tempDir.resolve("slow-content.kt")
        val target = tempDir.resolve("src/Slow.kt")
        Files.writeString(contentFile, "package sample\n\nclass Slow\n")
        val mutation = KastSemanticMutation.AddFile(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-slow"),
            request = KastAddFileRequest(
                workspaceRoot = tempDir.toString(),
                filePath = target.toString(),
                contentFile = contentFile.toString(),
            ),
        )
        val clock = TimeSource.Monotonic
        val operationStart = clock.markNow()

        val receipt = submit(dispatcher, mutation)
        val submissionElapsed = operationStart.elapsedNow()
        val terminal = awaitTerminal(
            dispatcher,
            KastMutationOperationSelector.ByOperationId(receipt.operation.operationId),
            attempts = 3_000,
        )

        assertTrue(submissionElapsed < 1.seconds, "submission took $submissionElapsed")
        assertTrue(operationStart.elapsedNow() > 10.seconds)
        assertTrue(terminal.state is KastMutationOperationState.Completed)
        assertEquals("package sample\n\nclass Slow\n", target.readText())
    }

    @Test
    fun `cancellation request retrieves typed terminal cancelled outcome`() = runBlocking {
        val applyStarted = CompletableDeferred<Unit>()
        val backend = CancellableApplyBackend(FakeAnalysisBackend.sample(tempDir), applyStarted)
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig())
        val contentFile = tempDir.resolve("cancel-content.kt")
        val target = tempDir.resolve("src/Cancelled.kt")
        Files.writeString(contentFile, "package sample\n\nclass Cancelled\n")
        val mutation = KastSemanticMutation.AddFile(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-cancel"),
            request = KastAddFileRequest(
                workspaceRoot = tempDir.toString(),
                filePath = target.toString(),
                contentFile = contentFile.toString(),
            ),
        )
        val receipt = submit(dispatcher, mutation)
        applyStarted.await()
        val selector = KastMutationOperationSelector.ByOperationId(receipt.operation.operationId)

        val acknowledged = dispatch(
            dispatcher = dispatcher,
            method = "mutation/cancel",
            params = json.encodeToJsonElement(KastMutationOperationSelector.serializer(), selector),
            serializer = KastMutationOperationSnapshot.serializer(),
        )
        val terminal = awaitTerminal(dispatcher, selector)

        assertTrue(acknowledged.state.cancellationRequested)
        val cancelled = terminal.state as KastMutationOperationState.Cancelled
        assertEquals(KastMutationEditApplicationState.STARTED, cancelled.trace.editApplicationState)
        assertFalse(terminal.safeForFilesystemFallback)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `applied scope response with invalid diagnostics is a typed failed operation`() = runBlocking {
        val backend = InvalidDiagnosticsBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig())
        val contentFile = tempDir.resolve("invalid-scope-content.kt")
        val target = tempDir.resolve("src/InvalidScope.kt")
        Files.writeString(contentFile, "package sample\n\nclass InvalidScope\n")
        val mutation = KastSemanticMutation.AddFile(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-invalid-scope"),
            request = KastAddFileRequest(
                workspaceRoot = tempDir.toString(),
                filePath = target.toString(),
                contentFile = contentFile.toString(),
            ),
        )

        val receipt = submit(dispatcher, mutation)
        val terminal = awaitTerminal(
            dispatcher,
            KastMutationOperationSelector.ByOperationId(receipt.operation.operationId),
        )

        val failed = terminal.state as KastMutationOperationState.Failed
        val invalid = failed.failure as KastMutationFailure.AppliedInvalidScope
        assertFalse(invalid.response.ok)
        assertTrue(invalid.response.applied)
        assertEquals(KastMutationEditApplicationState.COMPLETED, failed.trace.editApplicationState)
        assertTrue(Files.exists(target))
    }

    @Test
    fun `applied rename response with invalid diagnostics is a typed failed operation`() = runBlocking {
        val backend = InvalidDiagnosticsBackend(FakeAnalysisBackend.sample(tempDir))
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig())
        val target = tempDir.resolve("src/Sample.kt")
        val mutation = KastSemanticMutation.Rename(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-invalid-rename"),
            request = KastRenameBySymbolRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "greet",
                fileHint = target.toString(),
                newName = "hello",
            ),
        )

        val receipt = submit(dispatcher, mutation)
        val terminal = awaitTerminal(
            dispatcher,
            KastMutationOperationSelector.ByOperationId(receipt.operation.operationId),
        )

        val failed = terminal.state as KastMutationOperationState.Failed
        val invalid = failed.failure as KastMutationFailure.AppliedInvalidRename
        assertFalse(invalid.response.ok)
        assertEquals(KastMutationEditApplicationState.COMPLETED, failed.trace.editApplicationState)
        assertTrue(target.readText().contains("fun hello()"))
    }

    private suspend fun submit(
        dispatcher: RpcAnalysisDispatcher,
        mutation: KastSemanticMutation,
    ): KastMutationSubmissionReceipt = dispatch(
        dispatcher = dispatcher,
        method = "mutation/submit",
        params = json.encodeToJsonElement(KastSemanticMutation.serializer(), mutation),
        serializer = KastMutationSubmissionReceipt.serializer(),
    )

    private suspend fun status(
        dispatcher: RpcAnalysisDispatcher,
        selector: KastMutationOperationSelector,
    ): KastMutationOperationSnapshot = dispatch(
        dispatcher = dispatcher,
        method = "mutation/status",
        params = json.encodeToJsonElement(KastMutationOperationSelector.serializer(), selector),
        serializer = KastMutationOperationSnapshot.serializer(),
    )

    private suspend fun awaitTerminal(
        dispatcher: RpcAnalysisDispatcher,
        selector: KastMutationOperationSelector,
        attempts: Int = 400,
    ): KastMutationOperationSnapshot {
        repeat(attempts) {
            val snapshot = status(dispatcher, selector)
            if (
                snapshot.state is KastMutationOperationState.Completed ||
                snapshot.state is KastMutationOperationState.Failed ||
                snapshot.state is KastMutationOperationState.Cancelled
            ) {
                return snapshot
            }
            delay(5)
        }
        error("Mutation operation did not become terminal")
    }

    private suspend fun <T> dispatch(
        dispatcher: RpcAnalysisDispatcher,
        method: String,
        params: JsonElement,
        serializer: KSerializer<T>,
    ): T {
        val raw = dispatcher.dispatch(
            JsonRpcRequest(
                id = JsonPrimitive(1),
                method = method,
                params = params,
            ),
        )
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        return json.decodeFromJsonElement(serializer, response.result)
    }
}

private class DelayedApplyBackend(
    private val delegate: AnalysisBackend,
    private val delayMillis: Long,
) : AnalysisBackend by delegate {
    override suspend fun applyEdits(query: ParsedApplyEditsQuery) = run {
        delay(delayMillis)
        delegate.applyEdits(query)
    }
}

private class CancellableApplyBackend(
    delegate: AnalysisBackend,
    private val applyStarted: CompletableDeferred<Unit>,
) : AnalysisBackend by delegate {
    override suspend fun applyEdits(query: ParsedApplyEditsQuery) = run {
        applyStarted.complete(Unit)
        awaitCancellation()
    }
}

private class InvalidDiagnosticsBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val filePath = query.filePaths.value.first().value
        return DiagnosticsResult(
            diagnostics = listOf(
                Diagnostic(
                    location = Location(
                        filePath = filePath,
                        startOffset = 0,
                        endOffset = 1,
                        startLine = 1,
                        startColumn = 1,
                        preview = "invalid",
                    ),
                    severity = DiagnosticSeverity.ERROR,
                    message = "Synthetic invalid mutation diagnostic",
                ),
            ),
        )
    }
}
