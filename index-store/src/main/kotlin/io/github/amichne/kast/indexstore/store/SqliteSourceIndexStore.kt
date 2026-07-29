package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSnapshot
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSummary
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphScopeSnapshot
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphWriteResult
import io.github.amichne.kast.indexstore.api.index.*
import io.github.amichne.kast.indexstore.api.reference.*
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.FileStageFailureUpdate
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageRemoval
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.snapshot.*
import java.nio.file.Path

/**
 * SQLite-backed store for the source identifier index, file manifest,
 * symbol references, and workspace discovery cache.
 *
 * All data lives in a single `source-index.db` database under the kast cache
 * directory. WAL journal mode is enabled so readers never block writers.
 */
class SqliteSourceIndexStore private constructor(
    workspaceIdentity: WorkspaceIdentity,
    private val pageReadObserver: SourceIndexPageReadObserver,
) : AutoCloseable, SourceIndexWriter {
    constructor(workspaceIdentity: WorkspaceIdentity) : this(workspaceIdentity, SourceIndexPageReadObserver.Disabled)

    constructor(workspaceRoot: Path) : this(WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot))

    internal constructor(
        workspaceRoot: Path,
        pageReadObserver: SourceIndexPageReadObserver,
    ) : this(WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot), pageReadObserver)

    private val state = SqliteSourceIndexStoreState(workspaceIdentity, pageReadObserver)
    private val schema = state.schema

    private val fileMutations = SourceIndexFileMutations(state)
    private val files = SourceIndexFileStore(state, fileMutations)
    private val inventory = SourceIndexInventoryStore(state, fileMutations, files)
    private val referenceQuery = SourceIndexReferenceQuery(state)
    private val references = SourceIndexReferenceStore(state, fileMutations, files)
    private val declarationStore = SourceIndexDeclarationStore(state, fileMutations)
    private val fileStages = FileStageInventoryStore(state, fileMutations)
    private val semanticGraphWriter = SemanticGraphWriter(state)
    private val fileStageBatches = FileStageBatchStore(
        state = state,
        mutations = fileMutations,
        references = references,
        declarations = declarationStore,
        stages = fileStages,
        semanticGraph = semanticGraphWriter,
    )
    private val pendingUpdates = SourceIndexPendingUpdateStore(state, fileMutations, references)
    private val semanticGraphReader = SemanticGraphReader(state)
    private val snapshots = SourceIndexSnapshotStore(state)

    fun dbExists(): Boolean = state.dbExists()

    override fun close() = state.close()

    fun ensureSchema(): Boolean = schema.ensureSchema()

    fun saveFullIndex(
        updates: List<FileIndexUpdate>,
        manifest: Map<String, Long>,
    ) = files.saveFullIndex(updates, manifest)

    override fun saveFileIndex(update: FileIndexUpdate) = files.saveFileIndex(update)

    override fun removeFile(path: String) = files.removeFile(path)

    fun loadSourceIndexSnapshot(): SourceIndexSnapshot = files.loadSourceIndexSnapshot()

    fun gradleProjectsForFile(path: String): Set<BuildQualifiedGradleProjectIdentity> =
        files.gradleProjectsForFile(path)

    fun gradleSourceSetsForFile(path: String): Set<BuildQualifiedGradleSourceSetIdentity> =
        files.gradleSourceSetsForFile(path)

    fun packageEvidenceForFile(path: String): IndexedPackageEvidence? =
        files.packageEvidenceForFile(path)

    fun saveManifest(entries: Map<String, Long>) = inventory.saveManifest(entries)

    fun updateManifestEntry(
        path: String,
        lastModifiedMillis: Long,
    ) = inventory.updateManifestEntry(path, lastModifiedMillis)

    fun loadManifest(): Map<String, Long>? = inventory.loadManifest()

    fun knownSourcePaths(): List<Path> = inventory.knownSourcePaths()

    fun fileCountBySourceRoot(sourceRoots: Collection<Path>): Map<Path, Int> =
        inventory.fileCountBySourceRoot(sourceRoots)

    fun filesBySourceRoot(
        sourceRoots: Collection<Path>,
        limitPerRoot: Int? = null,
    ): Map<Path, List<Path>> = inventory.filesBySourceRoot(sourceRoots, limitPerRoot)

    fun moduleIndexStatus(moduleName: String): RelationshipIndexStatus? = inventory.moduleIndexStatus(moduleName)

    fun moduleIndexStatuses(): Map<String, RelationshipIndexStatus> = inventory.moduleIndexStatuses()

    fun completedModules(): Set<String> = inventory.completedModules()

    fun reconcileFileInventory(entries: Collection<FileInventoryEntry>, versions: FileStageVersions) =
        fileStages.reconcileFileInventory(entries, versions)

    fun pendingFileStages(stage: FileIndexStage): List<PendingFileStage> =
        fileStages.pendingFileStages(stage)

    fun pendingFileStage(
        path: String,
        contentHash: FileContentHash,
        stage: FileIndexStage,
        version: FileStageVersion,
        inputFingerprint: FileStageInputFingerprint? = null,
    ): PendingFileStage? = fileStages.pendingFileStage(path, contentHash, stage, version, inputFingerprint)

    fun fileStageOutcome(path: String, stage: FileIndexStage): FileStageOutcome? =
        fileStages.fileStageOutcome(path, stage)

    fun fileStageScopeCoverage(stage: FileIndexStage, path: String): FileStageScopeCoverage =
        fileStages.fileStageScopeCoverage(stage, path)

    fun fileStageScopeCoverage(stage: FileIndexStage, paths: Collection<String>): FileStageScopeCoverage =
        fileStages.fileStageScopeCoverage(stage, paths)

    fun commitSourceBatch(updates: List<SourceFileStageUpdate>) =
        fileStageBatches.commitSourceBatch(updates)

    fun commitRelationshipBatch(
        updates: List<RelationshipFileStageUpdate>,
        failures: List<FileStageFailureUpdate> = emptyList(),
    ) = fileStageBatches.commitRelationshipBatch(updates, failures)

    fun externalizeFileStageFailure(
        failureId: FileStageFailureId,
    ): FileStageFailureExternalizationResult =
        fileStageBatches.externalizeFileStageFailure(failureId)

    fun upsertSymbolReference(
        sourcePath: String,
        sourceOffset: Int,
        targetFqName: String,
        targetPath: String?,
        targetOffset: Int?,
        sourceFqName: String? = null,
        edgeKind: EdgeKind = EdgeKind.UNKNOWN,
    ) = references.upsertSymbolReference(
        sourcePath = sourcePath,
        sourceOffset = sourceOffset,
        targetFqName = targetFqName,
        targetPath = targetPath,
        targetOffset = targetOffset,
        sourceFqName = sourceFqName,
        edgeKind = edgeKind,
    )

    fun referencesToSymbol(targetFqName: String): List<SymbolReferenceRow> =
        referenceQuery.referencesToSymbol(targetFqName)

    fun generatedReferencePageToSymbol(
        targetFqName: String,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): GeneratedSymbolReferencePage =
        referenceQuery.generatedReferencePageToSymbol(targetFqName, offset, maxResults)

    fun generatedReferencePageToExactSymbol(
        target: ExactReferenceTarget,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): GeneratedSymbolReferencePage =
        referenceQuery.generatedReferencePageToExactSymbol(target, offset, maxResults)

    fun referencesFromFile(sourcePath: String): List<SymbolReferenceRow> =
        referenceQuery.referencesFromFile(sourcePath)

    fun clearReferencesFromFile(sourcePath: String) = references.clearReferencesFromFile(sourcePath)

    fun removeReferencesOutsideSources(sourcePaths: Collection<String>) =
        references.removeReferencesOutsideSources(sourcePaths)

    fun replaceReferencesFromFiles(referencesBySource: List<Pair<String, List<SymbolReferenceRow>>>) =
        references.replaceReferencesFromFiles(referencesBySource)

    fun replaceDeclarationsFromFile(
        filePath: String,
        declarations: List<DeclarationRow>,
    ) = declarationStore.replaceDeclarationsFromFile(filePath, declarations)

    fun replaceDeclarationsFromFiles(declarationsBySource: List<Pair<String, List<DeclarationRow>>>) =
        declarationStore.replaceDeclarationsFromFiles(declarationsBySource)

    fun declarationsWithSupertype(supertypeFqName: String): List<DeclarationRow> =
        declarationStore.declarationsWithSupertype(supertypeFqName)

    fun searchDeclarations(
        pattern: io.github.amichne.kast.api.contract.NonBlankString,
        maxResults: PositiveInt,
    ): List<DeclarationRow> = declarationStore.searchDeclarations(pattern, maxResults)

    fun appendPendingUpdate(
        op: String,
        path: String,
        payload: String?,
        sessionId: String? = null,
    ) = pendingUpdates.appendPendingUpdate(op, path, payload, sessionId)

    fun reconcilePendingUpdates(): Int = pendingUpdates.reconcilePendingUpdates()

    fun readWorkspaceDiscovery(cacheKey: String): String? = snapshots.readWorkspaceDiscovery(cacheKey)

    fun writeWorkspaceDiscovery(cacheKey: String, schemaVersion: Int, payload: String) =
        snapshots.writeWorkspaceDiscovery(cacheKey, schemaVersion, payload)

    fun replaceSemanticGraphFiles(
        updates: List<SemanticGraphFileIndexUpdate>,
        removedPaths: List<SemanticGraphSourcePath> = emptyList(),
    ): SemanticGraphWriteResult = semanticGraphWriter.replaceSemanticGraphFiles(updates, removedPaths)

    fun replaceSemanticGraphFilesIfGeneration(
        expectedGeneration: SourceIndexGeneration,
        updates: List<SemanticGraphFileIndexUpdate>,
        removedPaths: List<SemanticGraphSourcePath> = emptyList(),
    ): SemanticGraphCommitResult = semanticGraphWriter.replaceSemanticGraphFilesIfGeneration(
        expectedGeneration = expectedGeneration,
        updates = updates,
        removedPaths = removedPaths,
    )

    fun commitSemanticGraphBatchIfGeneration(
        expectedGeneration: SourceIndexGeneration,
        updates: List<SemanticGraphFileStageUpdate>,
        removals: List<SemanticGraphFileStageRemoval> = emptyList(),
    ): SemanticGraphCommitResult = semanticGraphWriter.replaceSemanticGraphFilesIfGeneration(
        expectedGeneration = expectedGeneration,
        updates = updates.map(SemanticGraphFileStageUpdate::update),
        removedPaths = removals.map(SemanticGraphFileStageRemoval::sourcePath),
        commitStageState = { conn ->
            fileStageBatches.commitSemanticStageStateInTransaction(conn, updates, removals)
        },
    )

    fun readSemanticGraph(filePaths: Collection<SemanticGraphSourcePath>): SemanticGraphIndexSnapshot =
        semanticGraphReader.readSemanticGraph(filePaths)

    fun readSemanticGraphSummary(filePaths: Collection<SemanticGraphSourcePath>): SemanticGraphIndexSummary =
        semanticGraphReader.readSemanticGraphSummary(filePaths)

    fun semanticGraphSymbolKeys(): Set<SemanticGraphSymbolKey> =
        semanticGraphReader.semanticGraphSymbolKeys()

    fun semanticGraphSourcePaths(): Set<SemanticGraphSourcePath> =
        semanticGraphReader.semanticGraphSourcePaths()

    fun semanticGraphScopeSnapshot(): SemanticGraphScopeSnapshot =
        semanticGraphReader.semanticGraphScopeSnapshot()

    fun readGeneration(): SourceIndexGeneration = snapshots.readGeneration()

    fun exportSnapshotDatabase(
        target: Path,
        treeOid: GitObjectId,
        producerVersion: ProducerVersion,
    ): PublicationEvidence = snapshots.exportSnapshotDatabase(target, treeOid, producerVersion)

    fun readHeadCommit(): String? = snapshots.readHeadCommit()

    fun writeHeadCommit(sha: String) = snapshots.writeHeadCommit(sha)
}
