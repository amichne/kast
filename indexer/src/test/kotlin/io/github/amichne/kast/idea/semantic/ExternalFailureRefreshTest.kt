package io.github.amichne.kast.idea.backend

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshExternalFailureStatus
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.fileInventoryEntry
import io.github.amichne.kast.idea.fileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class ExternalFailureRefreshTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
        private const val missingFailureId = "00000000-0000-0000-0000-000000000452"
    }

    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace refresh externalizes each failure ID through the semantic graph store`() = runBlocking {
        val failedPath = workspaceRoot.resolve("src/Failed.kt").toAbsolutePath().normalize()
        Files.createDirectories(failedPath.parent)
        Files.writeString(failedPath, "package demo")
        val contentHash = FileContentHash.parse("a".repeat(64))

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                entries = listOf(
                    fileInventoryEntry(
                        workspaceRoot = workspaceRoot,
                        path = failedPath.toString(),
                        lastModifiedMillis = 1,
                        contentHash = contentHash,
                        moduleName = ":app[main]",
                        sourceSet = "main",
                    ),
                ),
                versions = FileStageVersions.CURRENT,
            )
            ReferenceIndexer(store).indexPendingSymbolRelationships(
                work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS),
                scanner = {
                    RelationshipScanResult.Failed(
                        contentHash = contentHash,
                        code = FileStageFailureCode.PSI_UNAVAILABLE,
                        message = "Kotlin PSI is unavailable for this file",
                    )
                },
            )
            val failureId = requireNotNull(
                store.fileStageOutcome(failedPath.toString(), FileIndexStage.RELATIONSHIPS)?.failure,
            ).id.value

            KastIndexerBackend(
                project = projectFixture.get(),
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 500,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                semanticGraphStore = store,
            ).use { backend ->
                val first = backend.refresh(
                    RefreshQuery(
                        externalFailureIds = listOf(failureId, missingFailureId),
                    ).parsed(),
                )
                val second = backend.refresh(
                    RefreshQuery(externalFailureIds = listOf(failureId)).parsed(),
                )

                assertEquals(
                    listOf(
                        RefreshExternalFailureStatus.EXTERNALIZED,
                        RefreshExternalFailureStatus.NOT_FOUND,
                    ),
                    first.externalFailureOutcomes.map { it.status },
                )
                assertEquals(
                    listOf(failureId, missingFailureId),
                    first.externalFailureOutcomes.map { it.failureId.value },
                )
                assertEquals(
                    listOf(RefreshExternalFailureStatus.ALREADY_EXTERNAL),
                    second.externalFailureOutcomes.map { it.status },
                )
            }

            assertEquals(
                FileStageOutcomeStatus.EXTERNAL_BOUNDARY,
                store.fileStageOutcome(failedPath.toString(), FileIndexStage.RELATIONSHIPS)?.status,
            )
        }
    }
}
