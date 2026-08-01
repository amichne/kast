package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.FileStageWorkReason
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageFailureUpdate
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RepeatedSemanticGraphFailureTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `repeated PSI failure removes stale graph and remains durably retryable`() {
        val outcomePath = workspaceRoot.resolve("src/App.kt").toString()
        val sourcePath = SemanticGraphSourcePath.parse("src/App.kt")
        val contentHash = hash('a')
        val initialInput = fingerprint('1')
        val retryInput = fingerprint('2')

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(FileInventoryEntry(outcomePath, 1, contentHash, ":app[main]", "main")),
                FileStageVersions.CURRENT,
            )
            commitGraph(store, outcomePath, sourcePath, contentHash, initialInput, "stale")
            failGraph(store, pending(store, outcomePath, contentHash, retryInput), sourcePath)

            val failed = requireNotNull(store.fileStageOutcome(outcomePath, stage))
            assertEquals(FileStageOutcomeStatus.FAILED, failed.status)
            assertEquals(FileStageFailureCode.PSI_UNAVAILABLE, failed.failure?.code)
            assertTrue(store.readSemanticGraph(listOf(sourcePath)).symbols.isEmpty())
            assertEquals(FileStageWorkReason.PENDING, pending(store, outcomePath, contentHash, retryInput).reason)
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            failGraph(store, pending(store, outcomePath, contentHash, retryInput), sourcePath)

            val limited = requireNotNull(store.fileStageOutcome(outcomePath, stage))
            assertEquals(FileStageOutcomeStatus.LIMITED, limited.status)
            assertEquals(listOf(FileStageLimitation.PSI_UNAVAILABLE), limited.limitations)
            assertNull(limited.failure)
            val retry = store.retryableLimitedSemanticGraphStages().single()
            assertEquals(outcomePath, retry.path)
            assertEquals(retryInput, retry.inputFingerprint)
            assertEquals(FileStageWorkReason.LIMITED_RETRY, retry.reason)

            val staleGeneration = store.readGeneration()
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(
                        SemanticGraphSourcePath.parse("src/Other.kt"),
                        "b",
                        listOf(semanticSymbol("other#symbol", "other", SemanticGraphSourcePath.parse("src/Other.kt"))),
                    ),
                ),
            )
            val conflict = store.commitSemanticGraphBatchIfGeneration(
                expectedGeneration = staleGeneration,
                failures = listOf(failure(retry, sourcePath)),
            )
            assertTrue(conflict is SemanticGraphCommitResult.GenerationChanged)
            assertEquals(FileStageOutcomeStatus.LIMITED, store.fileStageOutcome(outcomePath, stage)?.status)

            commitGraph(store, retry, sourcePath, "current")
            assertEquals(FileStageOutcomeStatus.COMPLETE, store.fileStageOutcome(outcomePath, stage)?.status)
            assertTrue(store.retryableLimitedSemanticGraphStages().isEmpty())
            assertEquals(
                listOf("current"),
                store.readSemanticGraph(listOf(sourcePath)).symbols.map { symbol -> symbol.name.value },
            )
        }
    }

    private fun failGraph(
        store: SqliteSourceIndexStore,
        work: PendingFileStage,
        sourcePath: SemanticGraphSourcePath,
    ) {
        val result = store.commitSemanticGraphBatchIfGeneration(
            expectedGeneration = store.readGeneration(),
            failures = listOf(failure(work, sourcePath)),
        )
        assertTrue(result is SemanticGraphCommitResult.Committed)
    }

    private fun failure(
        work: PendingFileStage,
        sourcePath: SemanticGraphSourcePath,
    ) = SemanticGraphFileStageFailureUpdate(
        work = work,
        scannedContentHash = work.contentHash,
        sourcePath = sourcePath,
        code = FileStageFailureCode.PSI_UNAVAILABLE,
        message = "Kotlin PSI or diagnostics are unavailable for this file",
    )

    private fun commitGraph(
        store: SqliteSourceIndexStore,
        outcomePath: String,
        sourcePath: SemanticGraphSourcePath,
        contentHash: FileContentHash,
        inputFingerprint: FileStageInputFingerprint,
        symbolName: String,
    ) = commitGraph(store, pending(store, outcomePath, contentHash, inputFingerprint), sourcePath, symbolName)

    private fun commitGraph(
        store: SqliteSourceIndexStore,
        work: PendingFileStage,
        sourcePath: SemanticGraphSourcePath,
        symbolName: String,
    ) {
        val result = store.commitSemanticGraphBatchIfGeneration(
            expectedGeneration = store.readGeneration(),
            updates = listOf(
                SemanticGraphFileStageUpdate(
                    work = work,
                    update = semanticUpdate(
                        sourcePath,
                        work.contentHash.value.first().toString(),
                        listOf(semanticSymbol("demo#$symbolName", symbolName, sourcePath)),
                    ),
                ),
            ),
        )
        assertTrue(result is SemanticGraphCommitResult.Committed)
    }

    private fun pending(
        store: SqliteSourceIndexStore,
        outcomePath: String,
        contentHash: FileContentHash,
        inputFingerprint: FileStageInputFingerprint,
    ) = requireNotNull(
        store.pendingFileStage(outcomePath, contentHash, stage, version, inputFingerprint),
    )

    private fun fingerprint(character: Char) =
        FileStageInputFingerprint.parse(character.toString().repeat(64))

    private fun hash(character: Char) = FileContentHash.parse(character.toString().repeat(64))

    private companion object {
        val stage = FileIndexStage.SEMANTIC_GRAPH
        val version: FileStageVersion = FileStageVersions.CURRENT.semanticGraph
    }
}
