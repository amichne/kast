package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageFailureExternalizationResult
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReferenceIndexerExternalBoundaryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `eligible scan failure is durable while unrelated file commits`() {
        val failedPath = file("src/Failed.kt")
        val indexedPath = file("src/Indexed.kt")
        val hashes = mapOf(
            failedPath to hash('a'),
            indexedPath to hash('b'),
        )
        var persistedFailureId: String? = null

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(inventory(hashes), versions())
            val indexedPaths = mutableListOf<String>()

            ReferenceIndexer(store).indexPendingSymbolRelationships(
                work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS),
                scanner = { path ->
                    if (path == failedPath) {
                        RelationshipScanResult.Failed(
                            contentHash = hashes.getValue(path),
                            code = FileStageFailureCode.PSI_UNAVAILABLE,
                            message = "Kotlin PSI is unavailable for this file",
                        )
                    } else {
                        RelationshipScanResult.Indexed(
                            contentHash = hashes.getValue(path),
                            references = listOf(reference(path)),
                            declarations = emptyList(),
                        )
                    }
                },
                onFilesIndexed = indexedPaths::addAll,
            )

            val failed = requireNotNull(store.fileStageOutcome(failedPath, FileIndexStage.RELATIONSHIPS))
            assertEquals(FileStageOutcomeStatus.FAILED, failed.status)
            assertEquals(hashes.getValue(failedPath), failed.contentHash)
            assertEquals(FileStageFailureCode.PSI_UNAVAILABLE, requireNotNull(failed.failure).code)
            assertTrue(requireNotNull(failed.failure).id.value.isNotBlank())
            persistedFailureId = failed.failure.id.value

            assertEquals(listOf(indexedPath), indexedPaths)
            assertEquals(indexedPath, store.referencesToSymbol("demo.Target").single().sourcePath)
            assertEquals(
                setOf(failedPath),
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).mapTo(mutableSetOf()) { it.path },
            )
        }

        SqliteSourceIndexStore(workspaceRoot).use { reopened ->
            val failure = requireNotNull(
                reopened.fileStageOutcome(failedPath, FileIndexStage.RELATIONSHIPS)?.failure,
            )
            assertEquals(persistedFailureId, failure.id.value)
            assertEquals(FileStageFailureCode.PSI_UNAVAILABLE, failure.code)
            assertEquals(indexedPath, reopened.referencesToSymbol("demo.Target").single().sourcePath)
        }
    }

    @Test
    fun `externalization is replay safe and invalidated by content change`() {
        val path = file("src/Failed.kt")
        val initialHash = hash('a')
        val changedHash = hash('b')

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(inventory(mapOf(path to initialHash)), versions())
            ReferenceIndexer(store).indexPendingSymbolRelationships(
                work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS),
                scanner = {
                    RelationshipScanResult.Failed(
                        contentHash = initialHash,
                        code = FileStageFailureCode.PSI_UNAVAILABLE,
                        message = "Kotlin PSI is unavailable for this file",
                    )
                },
            )
            val failureId = requireNotNull(
                store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)?.failure,
            ).id
            val beforeExternalization = store.readGeneration()

            assertEquals(
                FileStageFailureExternalizationResult.EXTERNALIZED,
                store.externalizeFileStageFailure(failureId),
            )
            assertEquals(
                FileStageOutcomeStatus.EXTERNAL_BOUNDARY,
                store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)?.status,
            )
            assertTrue(store.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())
            val externalizedGeneration = store.readGeneration()
            assertTrue(externalizedGeneration.value > beforeExternalization.value)

            assertEquals(
                FileStageFailureExternalizationResult.ALREADY_EXTERNAL,
                store.externalizeFileStageFailure(failureId),
            )
            assertEquals(externalizedGeneration, store.readGeneration())

            store.reconcileFileInventory(inventory(mapOf(path to changedHash)), versions())
            val changedGeneration = store.readGeneration()
            assertNull(store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS))
            assertEquals(
                FileStageFailureExternalizationResult.NOT_FOUND,
                store.externalizeFileStageFailure(failureId),
            )
            assertEquals(changedGeneration, store.readGeneration())
            assertEquals(
                changedHash,
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single().contentHash,
            )
        }
    }

    private fun inventory(hashes: Map<String, FileContentHash>): List<FileInventoryEntry> =
        hashes.map { (path, contentHash) ->
            FileInventoryEntry(
                path = path,
                lastModifiedMillis = 1,
                contentHash = contentHash,
                moduleName = ":app[main]",
                sourceSet = "main",
            )
        }

    private fun versions(): FileStageVersions {
        val version = FileStageVersion.parse("external-boundary-test-1")
        return FileStageVersions(version, version, version)
    }

    private fun reference(path: String): SymbolReferenceRow =
        SymbolReferenceRow(
            sourcePath = path,
            sourceOffset = 1,
            targetFqName = "demo.Target",
            targetPath = null,
            targetOffset = null,
        )

    private fun file(relativePath: String): String {
        val path = workspaceRoot.resolve(relativePath).toAbsolutePath().normalize()
        Files.createDirectories(path.parent)
        Files.writeString(path, "package demo")
        return path.toString()
    }

    private fun hash(character: Char): FileContentHash =
        FileContentHash.parse(character.toString().repeat(64))
}
