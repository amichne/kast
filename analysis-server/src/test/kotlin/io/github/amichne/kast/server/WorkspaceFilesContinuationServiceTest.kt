package io.github.amichne.kast.server

import io.github.amichne.kast.api.continuation.ContinuationCapacity
import io.github.amichne.kast.api.continuation.ContinuationClock
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ContinuationTtl
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.contract.result.WorkspaceFilesContinuationResult
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFilesPageTokenException
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import io.github.amichne.kast.testing.FakeAnalysisBackend
import java.time.Duration
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceFilesContinuationServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dispatcher issues only an opaque handle and consumes plain state`() = runBlocking {
        val backend = FakeAnalysisBackend.sample(tempDir)
        val dispatcher = RpcAnalysisDispatcher(backend, AnalysisServerConfig())
        val identity = identity("source")
        val state = state(identity, "src/App.kt")
        val json = Json { explicitNulls = false }

        try {
            val issuedEnvelope = json.parseToJsonElement(
                dispatcher.dispatchRaw(
                    requestJson(json, WorkspaceFilesContinuationQuery.issue(identity, state)),
                ),
            ).jsonObject
            val issuedResult = issuedEnvelope.getValue("result").jsonObject
            assertEquals("ISSUED", issuedResult.getValue("type").jsonPrimitive.content)
            assertFalse("state" in issuedResult)
            val token = WorkspaceFilesPublicPageToken.parse(
                issuedResult.getValue("pageToken").jsonPrimitive.content,
            )

            val consumedEnvelope = json.parseToJsonElement(
                dispatcher.dispatchRaw(
                    requestJson(json, WorkspaceFilesContinuationQuery.consume(identity, token)),
                ),
            ).jsonObject
            val consumedResult = consumedEnvelope.getValue("result").jsonObject
            assertEquals("CONSUMED", consumedResult.getValue("type").jsonPrimitive.content)
            assertEquals(
                "src/App.kt",
                consumedResult.getValue("state").jsonObject
                    .getValue("lastRelativePath").jsonPrimitive.content,
            )

            val repeatedEnvelope = json.parseToJsonElement(
                dispatcher.dispatchRaw(
                    requestJson(json, WorkspaceFilesContinuationQuery.consume(identity, token)),
                ),
            ).jsonObject
            assertEquals(
                "INVALID_WORKSPACE_FILES_PAGE_TOKEN",
                repeatedEnvelope.getValue("error").jsonObject.getValue("data").jsonObject
                    .getValue("code").jsonPrimitive.content,
            )
        } finally {
            dispatcher.close()
            backend.close()
        }
    }

    @Test
    fun `issue returns an opaque handle and consume returns plain state exactly once`() {
        val token = WorkspaceFilesPublicPageToken.parse("00000000-0000-0000-0000-000000000001")
        val service = service(tokens = ArrayDeque(listOf(token)))
        val identity = identity("source")
        val state = state(identity, "src/App.kt")

        val issued = service.execute(WorkspaceFilesContinuationQuery.issue(identity, state).parsed())
        assertEquals(WorkspaceFilesContinuationResult.Issued(token), issued)

        val consumed = service.execute(WorkspaceFilesContinuationQuery.consume(identity, token).parsed())
        assertEquals(
            WorkspaceFilesContinuationResult.Consumed(state.toProjection()),
            consumed,
        )
        assertThrows(InvalidWorkspaceFilesPageTokenException::class.java) {
            service.execute(WorkspaceFilesContinuationQuery.consume(identity, token).parsed())
        }
        service.close()
    }

    @Test
    fun `query mismatch consumes the handle without exposing state`() {
        val token = WorkspaceFilesPublicPageToken.parse("00000000-0000-0000-0000-000000000002")
        val service = service(tokens = ArrayDeque(listOf(token)))
        val original = identity("source")
        val mismatched = identity("script")
        service.execute(WorkspaceFilesContinuationQuery.issue(original, state(original, "src/App.kt")).parsed())

        assertThrows(InvalidWorkspaceFilesPageTokenException::class.java) {
            service.execute(WorkspaceFilesContinuationQuery.consume(mismatched, token).parsed())
        }
        assertThrows(InvalidWorkspaceFilesPageTokenException::class.java) {
            service.execute(WorkspaceFilesContinuationQuery.consume(original, token).parsed())
        }
        service.close()
    }

    @Test
    fun `capacity and ttl invalidate handles through the shared policy`() {
        val first = WorkspaceFilesPublicPageToken.parse("00000000-0000-0000-0000-000000000003")
        val second = WorkspaceFilesPublicPageToken.parse("00000000-0000-0000-0000-000000000004")
        val clock = MutableClock()
        val service = service(
            tokens = ArrayDeque(listOf(first, second)),
            clock = clock,
            capacity = 1,
            ttl = Duration.ofSeconds(1),
        )
        val identity = identity("source")
        service.execute(WorkspaceFilesContinuationQuery.issue(identity, state(identity, "src/A.kt")).parsed())
        service.execute(WorkspaceFilesContinuationQuery.issue(identity, state(identity, "src/B.kt")).parsed())

        assertThrows(InvalidWorkspaceFilesPageTokenException::class.java) {
            service.execute(WorkspaceFilesContinuationQuery.consume(identity, first).parsed())
        }
        clock.advance(Duration.ofSeconds(1))
        assertThrows(InvalidWorkspaceFilesPageTokenException::class.java) {
            service.execute(WorkspaceFilesContinuationQuery.consume(identity, second).parsed())
        }
        service.close()
    }

    private fun service(
        tokens: ArrayDeque<WorkspaceFilesPublicPageToken>,
        clock: ContinuationClock = ContinuationClock.System,
        capacity: Int = 8,
        ttl: Duration = Duration.ofMinutes(1),
    ): WorkspaceFilesContinuationService = WorkspaceFilesContinuationService(
        capacity = ContinuationCapacity.of(capacity),
        timeToLive = ContinuationTtl.of(ttl),
        tokenIssuer = ContinuationTokenIssuer { tokens.removeFirst() },
        clock = clock,
    )

    private fun requestJson(
        json: Json,
        query: WorkspaceFilesContinuationQuery,
    ): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "raw/workspace-files-continuation")
        put("params", json.encodeToJsonElement(WorkspaceFilesContinuationQuery.serializer(), query))
        put("id", JsonPrimitive(1))
    }.toString()

    private fun identity(kind: String): WorkspaceFilesPublicContinuationIdentity =
        WorkspaceFilesPublicContinuationIdentity(
            workspaceRoot = WorkspaceFilesPublicContinuationIdentity.WorkspaceRoot.parse("/workspace"),
            backendName = WorkspaceFilesPublicContinuationIdentity.BackendName.parse("idea"),
            normalizedQuery = WorkspaceFilesPublicContinuationIdentity.NormalizedQuery.parse("kind=$kind"),
            projection = WorkspaceFilesPublicContinuationIdentity.Projection.parse("compact:*"),
            limit = WorkspaceFilesPublicContinuationIdentity.Limit.of(20),
        )

    private fun state(
        identity: WorkspaceFilesPublicContinuationIdentity,
        lastPath: String,
    ): WorkspaceFilesPublicContinuationState = WorkspaceFilesPublicContinuationState(
        identity = identity,
        compositionStampDigest =
            WorkspaceFilesPublicContinuationState.CompositionStampDigest.parse("b".repeat(64)),
        lastRelativePath = WorkspaceFilesPublicContinuationState.LastRelativePath.parse(lastPath),
        cumulativeReturnedCount = WorkspaceFilesPublicContinuationState.CumulativeReturnedCount.of(20),
    )

    private class MutableClock : ContinuationClock {
        private var nanos: Long = 0

        override fun nowNanos(): Long = nanos

        fun advance(duration: Duration) {
            nanos = Math.addExact(nanos, duration.toNanos())
        }
    }
}
