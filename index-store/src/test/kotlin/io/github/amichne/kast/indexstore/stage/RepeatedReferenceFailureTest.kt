package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepeatedReferenceFailureTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `third durable PSI failure becomes limited and remains retryable after cancellation`() {
        val path = workspaceRoot.resolve("src/App.kt").toAbsolutePath().normalize().also { file ->
            Files.createDirectories(file.parent)
            Files.writeString(file, "package demo")
        }.let { file -> workspaceSourceRawPath(workspaceRoot, file.toString()) }
        val hash = FileContentHash.parse("a".repeat(64))
        val initialVersions = versions("retry-1")
        val retryVersions = versions("retry-2")

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(listOf(inventory(path, hash)), initialVersions)
            ReferenceIndexer(store).indexPendingSymbolRelationships(
                work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS),
                scanner = { sourcePath ->
                    RelationshipScanResult.Indexed(
                        hash,
                        listOf(reference(sourcePath.rawPath, "demo.Stale")),
                        emptyList(),
                    )
                },
            )
            store.reconcileFileInventory(listOf(inventory(path, hash)), retryVersions)
            failCurrentRelationshipWork(store, hash)

            assertEquals(FileStageOutcomeStatus.FAILED, store.relationshipOutcome(path)?.status)
            assertEquals(1, store.relationshipOutcome(path)?.failureAttemptCount?.value)
            assertEquals(
                listOf(path),
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path.rawPath },
            )
            assertTrue(store.referencesFromFile(path).isEmpty())
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            failCurrentRelationshipWork(store, hash)

            val secondFailure = requireNotNull(store.relationshipOutcome(path))
            assertEquals(FileStageOutcomeStatus.FAILED, secondFailure.status)
            assertEquals(2, secondFailure.failureAttemptCount.value)
            assertEquals(
                listOf(path),
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path.rawPath },
            )
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            failCurrentRelationshipWork(store, hash)

            val limited = requireNotNull(store.relationshipOutcome(path))
            assertEquals(FileStageOutcomeStatus.LIMITED, limited.status)
            assertEquals(3, limited.failureAttemptCount.value)
            assertEquals(listOf(FileStageLimitation.PSI_UNAVAILABLE), limited.limitations)
            assertNull(limited.failure)
            assertTrue(store.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())
            assertEquals(listOf(path), store.retryableLimitedRelationshipStages().map { work -> work.path.rawPath })
            assertTrue(store.referencesFromFile(path).isEmpty())
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            ReferenceIndexer(store).retryLimitedSymbolRelationships(
                scanner = { error("Cancelled retry must not scan") },
                isCancelled = { true },
            )
            assertEquals(listOf(path), store.retryableLimitedRelationshipStages().map { work -> work.path.rawPath })

            ReferenceIndexer(store).retryLimitedSymbolRelationships(
                scanner = { sourcePath ->
                    RelationshipScanResult.Indexed(
                        hash,
                        listOf(reference(sourcePath.rawPath, "demo.Current")),
                        emptyList(),
                    )
                },
            )
            assertEquals(FileStageOutcomeStatus.COMPLETE, store.relationshipOutcome(path)?.status)
            assertTrue(store.retryableLimitedRelationshipStages().isEmpty())
            assertEquals("demo.Current", store.referencesFromFile(path).single().targetFqName)
        }
    }

    private fun failCurrentRelationshipWork(store: SqliteSourceIndexStore, hash: FileContentHash) {
        ReferenceIndexer(store).indexPendingSymbolRelationships(
            work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS),
            scanner = {
                RelationshipScanResult.Failed(
                    contentHash = hash,
                    code = FileStageFailureCode.PSI_UNAVAILABLE,
                    message = "Kotlin PSI is unavailable for this file",
                )
            },
        )
    }

    private fun SqliteSourceIndexStore.relationshipOutcome(path: String) =
        fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)

    private fun inventory(path: String, hash: FileContentHash) =
        fileInventoryEntry(workspaceRoot, path, 1, hash, ":app[main]", "main")

    private fun versions(value: String): FileStageVersions {
        val version = FileStageVersion.parse(value)
        return FileStageVersions(version, version, version)
    }

    private fun reference(path: String, targetFqName: String) =
        SymbolReferenceRow(
            sourcePath = path,
            sourceOffset = 1,
            targetFqName = targetFqName,
            targetPath = null,
            targetOffset = null,
        )
}
