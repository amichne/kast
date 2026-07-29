package io.github.amichne.kast.idea.backend.semantic

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.result.SemanticGraphCoverage
import io.github.amichne.kast.api.contract.result.SemanticGraphDiagnosticEvidence
import io.github.amichne.kast.api.contract.result.SemanticGraphFileCoverage
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphGeneration
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.idea.IdeaFileHashComputer
import io.github.amichne.kast.idea.IdeaReadEpochKind
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.diagnostics.analyzeDiagnosticsFileInReadEpoch
import io.github.amichne.kast.idea.runIdeaReadAction
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageRemoval
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageUpdate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal suspend fun KastPluginBackend.semanticGraphOperation(query: ParsedSemanticGraphQuery): SemanticGraphResult =
    withContext(readDispatcher) {
        val store = semanticGraphStore ?: throw CapabilityNotSupportedException(
            capability = "SEMANTIC_GRAPH",
            message = "Semantic graph extraction requires the IDEA source index",
        )
        store.ensureSchema()
        buildSemanticGraphSnapshot(query)
    }

private suspend fun KastPluginBackend.buildSemanticGraphSnapshot(query: ParsedSemanticGraphQuery): SemanticGraphResult {
    val store = requireNotNull(semanticGraphStore)
    val selectedPaths = query.filePaths.map(::toRelativeSemanticGraphPath)
    val removedPaths = query.removedFilePaths.map(::toRelativeSemanticGraphPath)
    val scopeSnapshot = store.semanticGraphScopeSnapshot()
    query.expectedGeneration?.let { expected ->
        if (expected.value != scopeSnapshot.generation.value) {
            throw semanticGraphGenerationConflict(expected.value, scopeSnapshot.generation.value)
        }
    }
    val semanticScope = (scopeSnapshot.sourcePaths - removedPaths.toSet()) + selectedPaths
    val stageInputs = currentSemanticGraphStageInputs(semanticScope)
    val contentHashes = stageInputs.associate { input -> input.sourcePath to input.contentHash }
    val stageInputFingerprint = semanticGraphStageInputFingerprint(stageInputs)
    val coverage = mutableListOf<SemanticGraphFileCoverage>()
    var omittedExternalTargetCount = 0
    val planned = query.filePaths.zip(selectedPaths).map { (absolutePath, relativePath) ->
        checkSemanticGraphCancellation()
        val contentHash = checkNotNull(contentHashes[relativePath]) {
            "Semantic graph scope has no current content hash for ${relativePath.value}"
        }
        PlannedSemanticGraphFile(
            absolutePath = absolutePath,
            relativePath = relativePath,
            contentHash = contentHash,
            work = store.pendingFileStage(
                path = absolutePath.value.value,
                contentHash = FileContentHash.parse(contentHash.value),
                stage = FileIndexStage.SEMANTIC_GRAPH,
                version = FileStageVersions.CURRENT.semanticGraph,
                inputFingerprint = stageInputFingerprint,
            ),
        )
    }
    val cachedFiles = store.readSemanticGraphSummary(selectedPaths).files.associateBy(SemanticGraphFileCoverage::path)
    planned.filter { file -> file.work == null }.forEach { file ->
        val persisted = checkNotNull(cachedFiles[file.relativePath]) {
            "Committed semantic graph outcome has no graph facts for ${file.relativePath.value}"
        }
        coverage += persisted.copy(
            status = SemanticGraphFileStatus.CACHED,
        )
    }
    val removals = query.removedFilePaths.zip(removedPaths).map { (absolutePath, relativePath) ->
        SemanticGraphFileStageRemoval(
            outcomePath = absolutePath.value.value,
            sourcePath = relativePath,
        )
    }
    var expectedGeneration = scopeSnapshot.generation
    var uncommittedRemovals = removals
    planned.filter { file -> file.work != null }
        .chunked(semanticGraphBatchSize)
        .forEach { batch ->
            checkSemanticGraphCancellation()
            val batchPsiGeneration = runIdeaReadAction { psiGeneration() }
            val refreshedBatch = batch.map { file ->
                val refreshed = runIdeaReadAction {
                    readEpochObserver.entered(IdeaReadEpochKind.SEMANTIC_GRAPH)
                    val currentPsiGeneration = psiGeneration()
                    if (batchPsiGeneration != currentPsiGeneration) {
                        throw semanticGraphPsiGenerationConflict(batchPsiGeneration, currentPsiGeneration)
                    }
                    refreshSemanticGraphFile(
                        absolutePath = file.absolutePath,
                        relativePath = file.relativePath,
                        expectedContentHash = file.contentHash,
                        semanticScope = semanticScope,
                    )
                }
                checkSemanticGraphCancellation()
                file to refreshed
            }
            val updates = refreshedBatch.map { (file, refreshed) ->
                SemanticGraphFileStageUpdate(
                    work = requireNotNull(file.work),
                    update = refreshed.extracted.update,
                    limitations = refreshed.extracted.limitations,
                )
            }
            val commit = runIdeaReadAction {
                val currentPsiGeneration = psiGeneration()
                if (batchPsiGeneration != currentPsiGeneration) {
                    throw semanticGraphPsiGenerationConflict(batchPsiGeneration, currentPsiGeneration)
                }
                store.commitSemanticGraphBatchIfGeneration(
                    expectedGeneration = expectedGeneration,
                    updates = updates,
                    removals = uncommittedRemovals,
                )
            }
            expectedGeneration = commit.semanticGraphGenerationOrThrow()
            uncommittedRemovals = emptyList()
            refreshedBatch.forEach { (_, refreshed) ->
                coverage += refreshed.coverage
                omittedExternalTargetCount = Math.addExact(
                    omittedExternalTargetCount,
                    refreshed.extracted.omittedExternalTargetCount,
                )
            }
    }
    if (uncommittedRemovals.isNotEmpty()) {
        val removalPsiGeneration = runIdeaReadAction { psiGeneration() }
        expectedGeneration = runIdeaReadAction {
            val currentPsiGeneration = psiGeneration()
            if (removalPsiGeneration != currentPsiGeneration) {
                throw semanticGraphPsiGenerationConflict(removalPsiGeneration, currentPsiGeneration)
            }
            store.commitSemanticGraphBatchIfGeneration(
                expectedGeneration = expectedGeneration,
                updates = emptyList(),
                removals = uncommittedRemovals,
            ).semanticGraphGenerationOrThrow()
        }
    }
    coverage += removedPaths.map { path ->
        SemanticGraphFileCoverage(path, null, SemanticGraphFileStatus.REMOVED)
    }
    val graph = store.readSemanticGraphSummary(selectedPaths)
    if (graph.generation != expectedGeneration) {
        throw semanticGraphGenerationConflict(expectedGeneration.value, graph.generation.value)
    }
    return SemanticGraphResult(
        generation = SemanticGraphGeneration(graph.generation.value),
        scopeFingerprint = semanticGraphScopeFingerprint(selectedPaths, removedPaths),
        coverage = SemanticGraphCoverage(
            files = coverage.sortedBy(SemanticGraphFileCoverage::path),
            omittedExternalTargetCount = NonNegativeInt(omittedExternalTargetCount),
        ),
        symbolCount = NonNegativeInt(graph.symbolCount),
        edgeOccurrenceCount = NonNegativeInt(graph.edgeOccurrenceCount),
    )
}

private fun SemanticGraphCommitResult.semanticGraphGenerationOrThrow() = when (this) {
    is SemanticGraphCommitResult.Committed -> writeResult.generation
    is SemanticGraphCommitResult.GenerationChanged -> throw semanticGraphGenerationConflict(
        expectedGeneration.value,
        actualGeneration.value,
    )
}

private data class SemanticGraphStageInput(
    val sourcePath: SemanticGraphSourcePath,
    val contentHash: SemanticGraphSha256,
)

private suspend fun KastPluginBackend.currentSemanticGraphStageInputs(
    sourcePaths: Set<SemanticGraphSourcePath>,
): List<SemanticGraphStageInput> {
    val absolutePaths = sourcePaths.associateWith { sourcePath ->
        workspaceRoot.resolve(sourcePath.value).toString()
    }
    val contentHashes = IdeaFileHashComputer.currentHashes(absolutePaths.values)
        .associate { fileHash ->
            fileHash.filePath to SemanticGraphSha256.parse(fileHash.hash)
        }
    return absolutePaths.map { (sourcePath, absolutePath) ->
        SemanticGraphStageInput(
            sourcePath = sourcePath,
            contentHash = checkNotNull(contentHashes[absolutePath]) {
                "IDEA did not hash semantic graph scope file $absolutePath"
            },
        )
    }
}

private data class PlannedSemanticGraphFile(
    val absolutePath: SemanticGraphPath,
    val relativePath: SemanticGraphSourcePath,
    val contentHash: SemanticGraphSha256,
    val work: PendingFileStage?,
)

private fun KastPluginBackend.refreshSemanticGraphFile(
    absolutePath: SemanticGraphPath,
    relativePath: SemanticGraphSourcePath,
    expectedContentHash: SemanticGraphSha256,
    semanticScope: Set<SemanticGraphSourcePath>,
): RefreshedSemanticGraphFile {
    val file = findKtFile(absolutePath.value.value)
    val contentHash = sha256(file.text)
    if (contentHash != expectedContentHash) {
        throw ConflictException(
            message = "Kotlin content changed while semantic graph work was planned; retry the refresh",
            details = mapOf("filePath" to absolutePath.value.value),
        )
    }
    val diagnostics = analyzeDiagnosticsFileInReadEpoch(absolutePath.value)
    if (diagnostics.status.state.name != "ANALYZED") {
        throw ValidationException(
            "Kotlin diagnostics prevent semantic graph extraction for ${absolutePath.value.value}",
        )
    }
    val evidence = diagnostics.diagnostics.map { diagnostic ->
        SemanticGraphDiagnosticEvidence(
            severity = diagnostic.severity,
            message = NonBlankString(diagnostic.message),
            startOffset = ByteOffset(diagnostic.location.startOffset),
            endOffset = ByteOffset(diagnostic.location.endOffset),
            line = LineNumber(diagnostic.location.startLine.coerceAtLeast(1)),
        )
    }
    val extracted = extractSemanticGraphFile(file, relativePath, contentHash, evidence, semanticScope)
    return RefreshedSemanticGraphFile(
        extracted = extracted,
        coverage = SemanticGraphFileCoverage(
            path = relativePath,
            contentHash = contentHash,
            status = SemanticGraphFileStatus.REFRESHED,
            diagnostics = evidence,
        ),
    )
}

private data class RefreshedSemanticGraphFile(
    val extracted: ExtractedSemanticGraphFile,
    val coverage: SemanticGraphFileCoverage,
)

private suspend fun checkSemanticGraphCancellation() {
    currentCoroutineContext().ensureActive()
    ProgressManager.checkCanceled()
}

private fun semanticGraphGenerationConflict(
    expectedGeneration: Long,
    actualGeneration: Long,
): ConflictException = ConflictException(
    message = "Semantic graph generation changed from $expectedGeneration to $actualGeneration; retry the refresh",
    details = mapOf(
        "expectedGeneration" to expectedGeneration.toString(),
        "actualGeneration" to actualGeneration.toString(),
    ),
)

private fun semanticGraphPsiGenerationConflict(
    expectedGeneration: Long,
    actualGeneration: Long,
): ConflictException = ConflictException(
    message = "Kotlin PSI changed between semantic graph file reads; retry the refresh",
    details = mapOf(
        "expectedPsiGeneration" to expectedGeneration.toString(),
        "actualPsiGeneration" to actualGeneration.toString(),
    ),
)

private fun KastPluginBackend.toRelativeSemanticGraphPath(path: SemanticGraphPath): SemanticGraphSourcePath {
    val absolute = path.value.toJavaPath()
    if (!absolute.startsWith(workspaceRoot)) {
        throw ValidationException(
            "Semantic graph path is outside the active workspace: ${path.value.value}",
            details = mapOf("filePath" to path.value.value),
        )
    }
    return SemanticGraphSourcePath.parse(workspaceRoot.relativize(absolute).toString())
}

private fun sha256(value: String): SemanticGraphSha256 = SemanticGraphSha256.parse(
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) },
)

private fun semanticGraphStageInputFingerprint(
    inputs: List<SemanticGraphStageInput>,
): FileStageInputFingerprint = FileStageInputFingerprint.parse(
    sha256(
        buildString {
            inputs.sortedBy(SemanticGraphStageInput::sourcePath).forEach { input ->
                append("source:")
                    .append(input.sourcePath.value)
                    .append(':')
                    .append(input.contentHash.value)
                    .append('\n')
            }
        },
    ).value,
)

private fun semanticGraphScopeFingerprint(
    selectedPaths: List<SemanticGraphSourcePath>,
    removedPaths: List<SemanticGraphSourcePath>,
): SemanticGraphSha256 = sha256(
    buildString {
        selectedPaths.sorted().forEach { append("selected:").append(it.value).append('\n') }
        removedPaths.sorted().forEach { append("removed:").append(it.value).append('\n') }
    },
)
