package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationEditApplicationState
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationId
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationState
import io.github.amichne.kast.api.contract.mutation.KastMutationProgressStage
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastScopeMutationOperation
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.protocol.ConflictException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class MutationOperationRegistryTest {
    private val firstOperationId = KastMutationOperationId("00000000-0000-0000-0000-000000000001")

    @Test
    fun `same key and fingerprint execute once while different fingerprint conflicts`() = runBlocking {
        val executionCount = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = registry()
        val mutation = addFileMutation("issue-333-retry", "/workspace/Added.kt")
        val fingerprint = MutationFingerprint("same-request")

        val first = registry.submit(mutation, fingerprint) {
            executionCount.incrementAndGet()
            started.complete(Unit)
            release.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        started.await()
        val retry = registry.submit(mutation, fingerprint) {
            error("A deduplicated operation must not install another worker")
        }

        assertEquals(firstOperationId, first.operation.operationId)
        assertEquals(first.operation.operationId, retry.operation.operationId)
        assertFalse(first.deduplicated)
        assertTrue(retry.deduplicated)
        assertEquals(1, executionCount.get())
        assertThrows<ConflictException> {
            registry.submit(
                addFileMutation("issue-333-retry", "/workspace/Different.kt"),
                MutationFingerprint("different-request"),
            ) { MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess()) }
        }

        release.complete(Unit)
        val terminal = awaitTerminal(registry, KastMutationOperationSelector.ByOperationId(firstOperationId))
        assertTrue(terminal.state is KastMutationOperationState.Completed)
        assertEquals(
            terminal,
            registry.status(KastMutationOperationSelector.ByIdempotencyKey(mutation.idempotencyKey)),
        )
    }

    @Test
    fun `cancellation becomes terminal only after worker stops and is idempotent`() = runBlocking {
        val enteredIdentity = CompletableDeferred<Unit>()
        val workerStopped = CompletableDeferred<Unit>()
        val registry = registry()
        val mutation = addFileMutation("issue-333-cancel-before-edit", "/workspace/Added.kt")
        val receipt = registry.submit(mutation, MutationFingerprint("cancel-before-edit")) { reporter ->
            reporter.report(MutationProgressEvent.StageEntered(KastMutationProgressStage.IDENTITY_RESOLUTION))
            enteredIdentity.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                workerStopped.complete(Unit)
            }
        }
        enteredIdentity.await()
        val selector = KastMutationOperationSelector.ByOperationId(receipt.operation.operationId)

        val firstCancel = registry.cancel(selector)
        val secondCancel = registry.cancel(selector)

        assertTrue(firstCancel.state.cancellationRequested)
        assertTrue(secondCancel.state.cancellationRequested)
        workerStopped.await()
        val terminal = awaitTerminal(registry, selector)
        val cancelled = terminal.state as KastMutationOperationState.Cancelled
        assertEquals(KastMutationEditApplicationState.NOT_STARTED, cancelled.trace.editApplicationState)
        assertTrue(cancelled.trace.safeForFilesystemFallback)
        assertEquals(terminal, registry.cancel(selector))
        assertEquals(terminal, registry.status(selector))
    }

    @Test
    fun `cancellation after edit application retains completed edit fact`() = runBlocking {
        val editCompleted = CompletableDeferred<Unit>()
        val registry = registry()
        val mutation = addFileMutation("issue-333-cancel-after-edit", "/workspace/Added.kt")
        val receipt = registry.submit(mutation, MutationFingerprint("cancel-after-edit")) { reporter ->
            reporter.report(MutationProgressEvent.StageEntered(KastMutationProgressStage.EDIT_APPLICATION))
            reporter.report(MutationProgressEvent.EditApplicationCompleted)
            editCompleted.complete(Unit)
            awaitCancellation()
        }
        editCompleted.await()
        val selector = KastMutationOperationSelector.ByOperationId(receipt.operation.operationId)

        registry.cancel(selector)

        val terminal = awaitTerminal(registry, selector)
        val cancelled = terminal.state as KastMutationOperationState.Cancelled
        assertEquals(KastMutationEditApplicationState.COMPLETED, cancelled.trace.editApplicationState)
        assertFalse(cancelled.trace.safeForFilesystemFallback)
        assertNotEquals(KastMutationOperationState.Queued(), cancelled)
    }

    private fun registry(): MutationOperationRegistry = MutationOperationRegistry(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        operationIdFactory = { firstOperationId },
    )

    private fun addFileMutation(key: String, filePath: String): KastSemanticMutation.AddFile =
        KastSemanticMutation.AddFile(
            idempotencyKey = KastMutationIdempotencyKey(key),
            request = KastAddFileRequest(filePath = filePath, contentFile = "/tmp/content.kt"),
        )

    private fun scopeSuccess(): KastSemanticMutationResult.Scope = KastSemanticMutationResult.Scope(
        KastScopeMutationSuccessResponse(
            ok = true,
            operation = KastScopeMutationOperation.ADD_FILE,
            applied = true,
            affectedFiles = listOf("/workspace/Added.kt"),
            createdFiles = listOf("/workspace/Added.kt"),
            editCount = 1,
            importChanges = 0,
            diagnostics = KastDiagnosticsSummary(clean = true, errorCount = 0, warningCount = 0),
            logFile = "",
        ),
    )

    private suspend fun awaitTerminal(
        registry: MutationOperationRegistry,
        selector: KastMutationOperationSelector,
    ) = buildList {
        repeat(200) {
            val snapshot = registry.status(selector)
            if (
                snapshot.state is KastMutationOperationState.Completed ||
                snapshot.state is KastMutationOperationState.Failed ||
                snapshot.state is KastMutationOperationState.Cancelled
            ) {
                add(snapshot)
                return@buildList
            }
            delay(5)
        }
    }.single()
}
