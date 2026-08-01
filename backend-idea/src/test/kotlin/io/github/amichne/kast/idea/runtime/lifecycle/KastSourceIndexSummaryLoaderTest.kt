package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.KastIndexState
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.indexing.RelationshipScanResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KastSourceIndexSummaryLoaderTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `noncritical gaps are qualified while critical gaps are incomplete`() {
        val source = workspaceRoot.resolve("src/main/App.kt").toAbsolutePath().normalize().also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "package demo")
        }.toString()
        val hash = FileContentHash.parse("a".repeat(64))
        val version = FileStageVersion.parse("coverage-v1")

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(FileInventoryEntry(source, 1, hash, ":app[main]", "main")),
                FileStageVersions(version, version, version),
            )
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

            assertEquals(KastIndexState.DEGRADED, store.loadKastSourceIndexSummary().state)
            assertEquals(
                KastIndexState.FAILED,
                store.loadKastSourceIndexSummary(criticalPaths = setOf(source)).state,
            )
            assertEquals(
                KastIndexState.FAILED,
                store.loadKastSourceIndexSummary(unmatchedCriticalPatterns = listOf("missing/**")).state,
            )
        }
    }
}
