package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationAction
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFilesPageTokenException
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkspaceFilesContinuationContractTest {
    @Test
    fun `public page token accepts only canonical random handles`() {
        val token = WorkspaceFilesPublicPageToken.random()

        assertEquals(token, WorkspaceFilesPublicPageToken.parse(token.value))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesPublicPageToken.parse(token.value.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesPublicPageToken.parse("not-a-handle")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(WorkspaceFilesPublicPageToken.serializer(), "\"not-a-handle\"")
        }
    }

    @Test
    fun `generated continuation request samples cross the typed request boundary`() {
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.isDirectory(it.resolve("cli-rs")) }
        val samplesRoot = repoRoot.resolve(
            "cli-rs/protocol/source/requests/raw/workspace-files-continuation",
        )

        for (variant in listOf("ISSUE", "CONSUME")) {
            for (shape in listOf("minimal", "maximal")) {
                val request = Json.parseToJsonElement(
                    java.nio.file.Files.readString(samplesRoot.resolve("$variant/$shape.json")),
                ).jsonObject
                val query = Json.decodeFromString(
                    WorkspaceFilesContinuationQuery.serializer(),
                    request.getValue("params").toString(),
                )

                query.parsed()
            }
        }
    }

    @Test
    fun `issue and consume wire forms are disjoint`() {
        val identity = identity()
        val state = state(identity)
        val token = WorkspaceFilesPublicPageToken.random()

        WorkspaceFilesContinuationQuery.issue(identity, state).parsed()
        WorkspaceFilesContinuationQuery.consume(identity, token).parsed()

        assertThrows(ValidationException::class.java) {
            WorkspaceFilesContinuationQuery(
                action = WorkspaceFilesContinuationAction.ISSUE,
                identity = identity,
                state = null,
                pageToken = null,
            ).parsed()
        }
        assertThrows(ValidationException::class.java) {
            WorkspaceFilesContinuationQuery(
                action = WorkspaceFilesContinuationAction.CONSUME,
                identity = identity,
                state = state,
                pageToken = token.value,
            ).parsed()
        }
        assertThrows(InvalidWorkspaceFilesPageTokenException::class.java) {
            WorkspaceFilesContinuationQuery(
                action = WorkspaceFilesContinuationAction.CONSUME,
                identity = identity,
                pageToken = "malformed",
            ).parsed()
        }
    }

    @Test
    fun `continuation state rejects escaping relative paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesPublicContinuationState.LastRelativePath.parse("../outside.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesPublicContinuationState.LastRelativePath.parse("/absolute.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesPublicContinuationState.LastRelativePath.parse("C:relative.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(
                WorkspaceFilesPublicContinuationState.CompositionStampDigest.serializer(),
                "\"not-a-digest\"",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(WorkspaceFilesPublicContinuationIdentity.Limit.serializer(), "0")
        }
    }

    private fun identity(): WorkspaceFilesPublicContinuationIdentity =
        WorkspaceFilesPublicContinuationIdentity(
            workspaceRoot = WorkspaceFilesPublicContinuationIdentity.WorkspaceRoot.parse(
                Path.of("/workspace").toAbsolutePath().normalize().toString(),
            ),
            backendName = WorkspaceFilesPublicContinuationIdentity.BackendName.parse("idea"),
            normalizedQuery = WorkspaceFilesPublicContinuationIdentity.NormalizedQuery.parse(
                "kind=source;package=named:com.example",
            ),
            projection = WorkspaceFilesPublicContinuationIdentity.Projection.parse("compact:*"),
            limit = WorkspaceFilesPublicContinuationIdentity.Limit.of(20),
        )

    private fun state(
        identity: WorkspaceFilesPublicContinuationIdentity,
    ): WorkspaceFilesPublicContinuationState = WorkspaceFilesPublicContinuationState(
        identity = identity,
        compositionStampDigest =
            WorkspaceFilesPublicContinuationState.CompositionStampDigest.parse("a".repeat(64)),
        lastRelativePath = WorkspaceFilesPublicContinuationState.LastRelativePath.parse("src/App.kt"),
        cumulativeReturnedCount = WorkspaceFilesPublicContinuationState.CumulativeReturnedCount.of(20),
    )
}
