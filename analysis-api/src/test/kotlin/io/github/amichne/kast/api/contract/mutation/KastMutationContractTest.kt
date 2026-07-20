package io.github.amichne.kast.api.contract.mutation

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.skill.KastAddDeclarationRequest
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.contract.skill.KastAddImplementationRequest
import io.github.amichne.kast.api.contract.skill.KastAddStatementRequest
import io.github.amichne.kast.api.contract.skill.KastAtPlacementAnchor
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastFilePlacementScope
import io.github.amichne.kast.api.contract.skill.KastPlacementAnchor
import io.github.amichne.kast.api.contract.skill.KastPlacementSelector
import io.github.amichne.kast.api.contract.skill.KastRenameBySymbolRequest
import io.github.amichne.kast.api.contract.skill.KastReplaceDeclarationBySymbolRequest
import io.github.amichne.kast.api.contract.skill.KastScopeMutationOperation
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastStatementPlacementAnchor
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.util.UUID

class KastMutationContractTest {
    @Test
    fun `workspace task IDs require canonical UUID spelling`() {
        assertThrows<IllegalArgumentException> {
            KastWorkspaceTaskId("1-1-1-1-1")
        }
    }
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `operation identifiers reject malformed boundary values`() {
        val operationId = KastMutationOperationId(UUID.randomUUID().toString())
        val idempotencyKey = KastMutationIdempotencyKey("issue-333-add-file")

        assertEquals(operationId.value, operationId.toString())
        assertEquals(idempotencyKey.value, idempotencyKey.toString())
        assertThrows<IllegalArgumentException> { KastMutationOperationId("not-a-uuid") }
        assertThrows<IllegalArgumentException> { KastMutationIdempotencyKey(" ") }
        assertThrows<IllegalArgumentException> { KastMutationIdempotencyKey("x".repeat(129)) }
    }

    @Test
    fun `all public semantic mutation variants have stable wire identities`() {
        val key = KastMutationIdempotencyKey("issue-333")
        val taskId = KastWorkspaceTaskId("00000000-0000-0000-0000-000000000420")
        val placement = KastPlacementSelector(
            scope = KastFilePlacementScope("/workspace/Sample.kt"),
            anchor = KastAtPlacementAnchor(KastPlacementAnchor.FILE_BOTTOM),
        )
        val mutations = listOf(
            "RENAME" to KastSemanticMutation.Rename(
                workspaceTaskId = taskId,
                idempotencyKey = key,
                request = KastRenameBySymbolRequest(symbol = "sample.greet", newName = "welcome"),
            ),
            "ADD_FILE" to KastSemanticMutation.AddFile(
                workspaceTaskId = taskId,
                idempotencyKey = key,
                request = KastAddFileRequest(
                    filePath = "/workspace/Added.kt",
                    contentFile = "/tmp/Added.kt",
                ),
            ),
            "ADD_DECLARATION" to KastSemanticMutation.AddDeclaration(
                workspaceTaskId = taskId,
                idempotencyKey = key,
                request = KastAddDeclarationRequest(placement = placement, contentFile = "/tmp/declaration.kt"),
            ),
            "ADD_IMPLEMENTATION" to KastSemanticMutation.AddImplementation(
                workspaceTaskId = taskId,
                idempotencyKey = key,
                request = KastAddImplementationRequest(placement = placement, contentFile = "/tmp/implementation.kt"),
            ),
            "ADD_STATEMENT" to KastSemanticMutation.AddStatement(
                workspaceTaskId = taskId,
                idempotencyKey = key,
                request = KastAddStatementRequest(
                    insideScope = "sample.greet",
                    anchor = KastStatementPlacementAnchor.BODY_END,
                    contentFile = "/tmp/statement.kt",
                ),
            ),
            "REPLACE_DECLARATION" to KastSemanticMutation.ReplaceDeclaration(
                workspaceTaskId = taskId,
                idempotencyKey = key,
                request = KastReplaceDeclarationBySymbolRequest(
                    symbol = "sample.greet",
                    contentFile = "/tmp/replacement.kt",
                ),
            ),
        )

        mutations.forEach { (expectedType, mutation) ->
            val encoded = json.encodeToJsonElement(KastSemanticMutation.serializer(), mutation).jsonObject
            assertEquals(JsonPrimitive(expectedType), encoded["type"])
            assertEquals(JsonPrimitive(taskId.value), encoded["workspaceTaskId"])
            assertEquals(JsonPrimitive(key.value), encoded["idempotencyKey"])
            assertTrue(encoded["request"] != null)
            assertEquals(mutation, json.decodeFromJsonElement(KastSemanticMutation.serializer(), encoded))
        }
    }

    @Test
    fun `operation selectors round trip without nullable selector fields`() {
        val operationId = KastMutationOperationId(UUID.randomUUID().toString())
        val key = KastMutationIdempotencyKey("issue-333-selector")
        val selectors = listOf(
            KastMutationOperationSelector.ByOperationId(operationId),
            KastMutationOperationSelector.ByIdempotencyKey(key),
        )

        selectors.forEach { selector ->
            val encoded = json.encodeToString(KastMutationOperationSelector.serializer(), selector)
            assertEquals(selector, json.decodeFromString(KastMutationOperationSelector.serializer(), encoded))
        }
    }

    @Test
    fun `terminal operation states retain typed outcomes and edit facts`() {
        val trace = KastMutationExecutionTrace(
            enteredStages = listOf(
                KastMutationProgressStage.EDIT_APPLICATION,
                KastMutationProgressStage.WORKSPACE_REFRESH,
                KastMutationProgressStage.IMPORT_OPTIMIZATION,
                KastMutationProgressStage.DIAGNOSTICS,
            ),
            editApplicationState = KastMutationEditApplicationState.COMPLETED,
        )
        val success = scopeSuccess()
        val completed = KastMutationOperationState.Completed(
            result = success,
            trace = trace,
            cancellationRequested = false,
        )
        val failed = KastMutationOperationState.Failed(
            failure = KastMutationFailure.Thrown(
                ApiErrorResponse(
                    requestId = "operation",
                    code = "TEST_FAILURE",
                    message = "failed",
                    retryable = false,
                ),
            ),
            trace = trace,
            cancellationRequested = false,
        )
        val cancelled = KastMutationOperationState.Cancelled(
            message = "Cancellation acknowledged after execution stopped.",
            trace = trace,
            cancellationRequested = true,
        )

        assertEquals(success, completed.result)
        assertEquals(KastMutationEditApplicationState.COMPLETED, failed.trace.editApplicationState)
        assertTrue(cancelled.cancellationRequested)
    }

    @Test
    fun `filesystem fallback requires terminal no-write failure or cancellation`() {
        val operationId = KastMutationOperationId(UUID.randomUUID().toString())
        val key = KastMutationIdempotencyKey("issue-333-fallback")
        val noWriteTrace = KastMutationExecutionTrace()
        val identityTrace = noWriteTrace.entering(KastMutationProgressStage.IDENTITY_RESOLUTION)
        val completedTrace = KastMutationExecutionTrace()
            .entering(KastMutationProgressStage.EDIT_APPLICATION)
            .editApplicationCompleted()
        val thrown = KastMutationFailure.Thrown(
            ApiErrorResponse(
                requestId = operationId.value,
                code = "TEST_FAILURE",
                message = "failed",
                retryable = false,
            ),
        )
        fun snapshot(state: KastMutationOperationState) = KastMutationOperationSnapshot(
            operationId = operationId,
            idempotencyKey = key,
            mutationKind = KastSemanticMutationKind.ADD_FILE,
            state = state,
        )

        assertFalse(snapshot(KastMutationOperationState.Queued()).safeForFilesystemFallback)
        assertFalse(
            snapshot(
                KastMutationOperationState.Applying(
                    stage = KastMutationProgressStage.IDENTITY_RESOLUTION,
                    trace = identityTrace,
                    cancellationRequested = false,
                ),
            ).safeForFilesystemFallback,
        )
        assertFalse(
            snapshot(
                KastMutationOperationState.Completed(
                    result = scopeSuccess(),
                    trace = completedTrace,
                    cancellationRequested = false,
                ),
            ).safeForFilesystemFallback,
        )
        assertTrue(
            snapshot(
                KastMutationOperationState.Failed(
                    failure = thrown,
                    trace = noWriteTrace,
                    cancellationRequested = false,
                ),
            ).safeForFilesystemFallback,
        )
        assertTrue(
            snapshot(
                KastMutationOperationState.Cancelled(
                    message = "Stopped before edit application.",
                    trace = noWriteTrace,
                ),
            ).safeForFilesystemFallback,
        )
        assertFalse(
            snapshot(
                KastMutationOperationState.Failed(
                    failure = thrown,
                    trace = completedTrace,
                    cancellationRequested = false,
                ),
            ).safeForFilesystemFallback,
        )
    }

    private fun scopeSuccess(): KastSemanticMutationResult.Scope = KastSemanticMutationResult.Scope(
            KastScopeMutationSuccessResponse(
                ok = true,
                operation = KastScopeMutationOperation.ADD_FILE,
                applied = true,
                affectedFiles = listOf("/workspace/Added.kt"),
                createdFiles = listOf("/workspace/Added.kt"),
                editCount = 1,
                importChanges = 0,
                diagnostics = KastDiagnosticsSummary.from(
                    DiagnosticsResult.of(
                        diagnostics = emptyList(),
                        fileStatuses = listOf(
                            FileAnalysisStatus.analyzed(
                                NormalizedPath.ofAbsolute(Path.of("/workspace/Added.kt")),
                            ),
                        ),
                        fileHashes = listOf(
                            FileHash("/workspace/Added.kt", FileHashing.sha256("added")),
                        ),
                    ),
                ),
                logFile = "",
            ),
        )
}
