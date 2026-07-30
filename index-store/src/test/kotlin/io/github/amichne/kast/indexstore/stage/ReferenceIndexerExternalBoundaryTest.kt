package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.SemanticGraphExternalBoundaryReason
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageFailureExternalizationResult
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.RelationshipIndexStatus
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
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

    @Test
    fun `externalized relationship failure becomes a usable unknown graph boundary`() {
        val sourcePath = file("src/Source.kt")
        val failedPath = file("src/Failed.kt")
        val hashes = mapOf(sourcePath to hash('a'), failedPath to hash('b'))
        val sourceGraphPath = SemanticGraphSourcePath.parse("src/Source.kt")
        val failedGraphPath = SemanticGraphSourcePath.parse("src/Failed.kt")
        val staleGraphPath = SemanticGraphSourcePath.parse("src/Stale.kt")
        val sourceSymbol = semanticSymbol("source#call", "call", sourceGraphPath)
        val failedSymbol = semanticSymbol("failed#target", "target", failedGraphPath)
        val staleSymbol = semanticSymbol("stale#target", "stale", staleGraphPath)
        val inbound = relation(sourceSymbol.canonicalKey.value, failedSymbol.canonicalKey.value, sourceGraphPath)
        val staleOutgoing = relation(failedSymbol.canonicalKey.value, staleSymbol.canonicalKey.value, failedGraphPath)

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(inventory(hashes), versions("external-boundary-test-1"))
            ReferenceIndexer(store).indexPendingSymbolRelationships(
                work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS),
                scanner = { path ->
                    RelationshipScanResult.Indexed(
                        contentHash = hashes.getValue(path),
                        references = if (path == sourcePath) {
                            listOf(reference(path, "demo.Failed", failedPath))
                        } else {
                            listOf(reference(path, "demo.Stale"))
                        },
                        declarations = if (path == failedPath) listOf(declaration(path)) else emptyList(),
                    )
                },
            )
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(
                        path = sourceGraphPath,
                        hash = "a",
                        symbols = listOf(sourceSymbol),
                        boundarySymbols = listOf(failedSymbol),
                        relations = listOf(inbound),
                    ),
                    semanticUpdate(
                        path = failedGraphPath,
                        hash = "b",
                        symbols = listOf(failedSymbol),
                        boundarySymbols = listOf(staleSymbol),
                        relations = listOf(staleOutgoing),
                    ),
                ),
            )

            store.reconcileFileInventory(inventory(hashes), versions("external-boundary-test-2"))
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
                            references = listOf(reference(path, "demo.Failed", failedPath)),
                            declarations = emptyList(),
                        )
                    }
                },
            )
            val failure = requireNotNull(
                store.fileStageOutcome(failedPath, FileIndexStage.RELATIONSHIPS)?.failure,
            )

            assertEquals(
                FileStageFailureExternalizationResult.EXTERNALIZED,
                store.externalizeFileStageFailure(failure.id),
            )

            assertEquals(RelationshipIndexStatus.DEGRADED, store.moduleIndexStatus(":app[main]"))
            assertEquals(setOf(":app[main]"), store.completedModules())
            val coverage = store.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, sourcePath)
            assertTrue(coverage is FileStageScopeCoverage.Limited)
            coverage as FileStageScopeCoverage.Limited
            assertEquals(1, coverage.externalFiles)
            assertEquals(0, coverage.failedFiles)
            assertTrue(store.referencesFromFile(failedPath).isEmpty())
            assertEquals(sourcePath, store.referencesToSymbol("demo.Failed").single().sourcePath)
            assertTrue(store.searchDeclarations(NonBlankString("Failed"), PositiveInt(10)).isEmpty())

            assertNull(store.fileStageOutcome(failedPath, FileIndexStage.SEMANTIC_GRAPH))
            assertTrue(
                store.pendingFileStages(FileIndexStage.SEMANTIC_GRAPH).none { work -> work.path == failedPath },
            )

            val graph = store.readSemanticGraph(listOf(sourceGraphPath, failedGraphPath))
            val failedCoverage = graph.files.single { file -> file.path == failedGraphPath }
            assertEquals(SemanticGraphFileStatus.UNKNOWN, failedCoverage.status)
            assertEquals(failure.id.value, requireNotNull(failedCoverage.externalBoundary).failureId.value)
            assertEquals(
                SemanticGraphExternalBoundaryReason.PSI_UNAVAILABLE,
                failedCoverage.externalBoundary?.reason,
            )
            assertEquals(listOf(sourceSymbol), graph.symbols)
            assertEquals(listOf(failedSymbol), graph.boundarySymbols)
            assertEquals(listOf(inbound), graph.relations)
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

    private fun versions(label: String = "external-boundary-test-1"): FileStageVersions {
        val version = FileStageVersion.parse(label)
        return FileStageVersions(version, version, version)
    }
    private fun reference(
        path: String,
        targetFqName: String = "demo.Target",
        targetPath: String? = null,
    ): SymbolReferenceRow =
        SymbolReferenceRow(
            sourcePath = path,
            sourceOffset = 1,
            targetFqName = targetFqName,
            targetPath = targetPath,
            targetOffset = targetPath?.let { 0 },
        )

    private fun declaration(path: String): DeclarationRow =
        DeclarationRow(
            fqName = "demo.Failed",
            kind = DeclarationKind.CLASS,
            visibility = DeclarationVisibility.PUBLIC,
            filePath = path,
            declarationOffset = 0,
            modulePath = ":app",
            sourceSet = "main",
        )

    private fun relation(
        sourceKey: String,
        targetKey: String,
        sourcePath: SemanticGraphSourcePath,
    ): SemanticGraphRelation =
        SemanticGraphRelation(
            sourceKey = io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey.parse(sourceKey),
            targetKey = io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey.parse(targetKey),
            kind = SemanticGraphRelationKind.CALLS,
            sourcePath = sourcePath,
            startOffset = ByteOffset(0),
            endOffset = ByteOffset(1),
            line = LineNumber(1),
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
