package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

class SemanticGraphFileStageFingerprintTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `restart reuses the same semantic input and rejects a wider scope`() {
        val outcomePath = workspaceRoot.resolve("src/A.kt").toString()
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val first = fingerprint('1')
        val wider = fingerprint('2')

        val generation = SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            commit(store, outcomePath, sourcePath, first, "old")
            assertEquals(first, store.fileStageOutcome(outcomePath, stage)?.inputFingerprint)
            store.readGeneration()
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            assertNull(store.pendingFileStage(outcomePath, contentHash, stage, version, first))
            assertNotNull(store.pendingFileStage(outcomePath, contentHash, stage, version, wider))
            assertEquals(generation, store.readGeneration())
        }
    }

    @Test
    fun `failed semantic input replacement rolls back facts outcome and generation`() {
        val outcomePath = workspaceRoot.resolve("src/A.kt").toString()
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val first = fingerprint('1')
        val wider = fingerprint('2')

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            commit(store, outcomePath, sourcePath, first, "old")
            val generation = store.readGeneration()

            DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(workspaceRoot)}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """CREATE TRIGGER reject_semantic_outcome
                           BEFORE INSERT ON file_stage_outcomes
                           WHEN NEW.stage = 'SEMANTIC_GRAPH'
                           BEGIN
                               SELECT RAISE(FAIL, 'injected semantic outcome failure');
                           END""",
                    )
                }
            }

            assertThrows(SQLException::class.java) {
                commit(store, outcomePath, sourcePath, wider, "new")
            }

            assertEquals(generation, store.readGeneration())
            assertEquals(first, store.fileStageOutcome(outcomePath, stage)?.inputFingerprint)
            assertEquals(
                listOf("old"),
                store.readSemanticGraph(listOf(sourcePath)).symbols.map { symbol -> symbol.name.value },
            )
        }
    }

    private fun commit(
        store: SqliteSourceIndexStore,
        outcomePath: String,
        sourcePath: SemanticGraphSourcePath,
        inputFingerprint: FileStageInputFingerprint,
        symbolName: String,
    ) {
        val work = checkNotNull(
            store.pendingFileStage(outcomePath, contentHash, stage, version, inputFingerprint),
        )
        val result = store.commitSemanticGraphBatchIfGeneration(
            expectedGeneration = store.readGeneration(),
            updates = listOf(
                SemanticGraphFileStageUpdate(
                    work = work,
                    update = semanticUpdate(
                        sourcePath,
                        "a",
                        listOf(semanticSymbol("demo#$symbolName", symbolName, sourcePath)),
                    ),
                ),
            ),
        )
        assertTrue(result is SemanticGraphCommitResult.Committed)
    }

    private fun fingerprint(character: Char): FileStageInputFingerprint =
        FileStageInputFingerprint.parse(character.toString().repeat(64))

    private companion object {
        val contentHash: FileContentHash = FileContentHash.parse("a".repeat(64))
        val stage: FileIndexStage = FileIndexStage.SEMANTIC_GRAPH
        val version: FileStageVersion = FileStageVersion.parse("semantic-graph-1")
    }
}
