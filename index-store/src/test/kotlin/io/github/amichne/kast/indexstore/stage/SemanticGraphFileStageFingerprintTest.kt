package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageFailureUpdate
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageRemoval
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
    fun `semantic graph update path must match pending work`() {
        val work = SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            checkNotNull(
                store.pendingFileStage(
                    workspaceRoot.resolve("src/A.kt").toString(),
                    defaultContentHash,
                    stage,
                    version,
                    fingerprint('1'),
                ),
            )
        }
        val wrongPath = SemanticGraphSourcePath.parse("src/B.kt")

        assertThrows(IllegalArgumentException::class.java) {
            SemanticGraphFileStageUpdate(
                work = work,
                update = semanticUpdate(
                    wrongPath,
                    "a",
                    listOf(semanticSymbol("demo#wrong", "wrong", wrongPath)),
                ),
            )
        }
    }

    @Test
    fun `semantic graph failure and removal derive their graph path from workspace proof`() {
        val work = SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            checkNotNull(
                store.pendingFileStage(
                    workspaceRoot.resolve("src/A.kt").toString(),
                    defaultContentHash,
                    stage,
                    version,
                    fingerprint('1'),
                ),
            )
        }
        val expectedPath = SemanticGraphSourcePath.parse("src/A.kt")

        val failure = SemanticGraphFileStageFailureUpdate(
            work = work,
            scannedContentHash = work.contentHash,
            code = FileStageFailureCode.PSI_UNAVAILABLE,
            message = "Kotlin PSI is unavailable",
        )
        val removal = SemanticGraphFileStageRemoval(work.path)

        assertEquals(expectedPath, failure.sourcePath)
        assertEquals(expectedPath, removal.sourcePath)
    }

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
            assertNull(store.pendingFileStage(outcomePath, defaultContentHash, stage, version, first))
            assertNotNull(store.pendingFileStage(outcomePath, defaultContentHash, stage, version, wider))
            assertEquals(generation, store.readGeneration())
        }
    }

    @Test
    fun `one semantic content change invalidates only that file after restart`() {
        val firstPath = workspaceRoot.resolve("src/A.kt").toString()
        val siblingPath = workspaceRoot.resolve("src/B.kt").toString()
        val firstSource = SemanticGraphSourcePath.parse("src/A.kt")
        val siblingSource = SemanticGraphSourcePath.parse("src/B.kt")
        val input = fingerprint('1')
        val firstHash = hash('a')
        val siblingHash = hash('b')

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            commit(store, firstPath, firstSource, input, "first", firstHash)
            commit(store, siblingPath, siblingSource, input, "sibling", siblingHash)
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            assertNotNull(store.pendingFileStage(firstPath, hash('c'), stage, version, input))
            assertNull(store.pendingFileStage(siblingPath, siblingHash, stage, version, input))
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
        contentHash: FileContentHash = defaultContentHash,
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
                        contentHash.value.first().toString(),
                        listOf(semanticSymbol("demo#$symbolName", symbolName, sourcePath)),
                    ),
                ),
            ),
        )
        assertTrue(result is SemanticGraphCommitResult.Committed)
    }

    private fun fingerprint(character: Char): FileStageInputFingerprint =
        FileStageInputFingerprint.parse(character.toString().repeat(64))

    private fun hash(character: Char): FileContentHash =
        FileContentHash.parse(character.toString().repeat(64))

    private companion object {
        val defaultContentHash: FileContentHash = FileContentHash.parse("a".repeat(64))
        val stage: FileIndexStage = FileIndexStage.SEMANTIC_GRAPH
        val version: FileStageVersion = FileStageVersion.parse("semantic-graph-1")
    }
}
