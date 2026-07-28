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
import io.github.amichne.kast.idea.IdeaReadEpochKind
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.diagnostics.analyzeDiagnosticsFileInReadEpoch
import io.github.amichne.kast.idea.runIdeaReadAction
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
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
    val updates = mutableListOf<SemanticGraphFileIndexUpdate>()
    val coverage = mutableListOf<SemanticGraphFileCoverage>()
    var omittedExternalTargetCount = 0
    val snapshotPsiGeneration = runIdeaReadAction { psiGeneration() }

    query.filePaths.zip(selectedPaths).forEach { (absolutePath, relativePath) ->
        checkSemanticGraphCancellation()
        val refreshed = runIdeaReadAction {
            readEpochObserver.entered(IdeaReadEpochKind.SEMANTIC_GRAPH)
            val currentPsiGeneration = psiGeneration()
            if (snapshotPsiGeneration != currentPsiGeneration) {
                throw semanticGraphPsiGenerationConflict(snapshotPsiGeneration, currentPsiGeneration)
            }
            refreshSemanticGraphFile(
                absolutePath = absolutePath,
                relativePath = relativePath,
                semanticScope = semanticScope,
            )
        }
        checkSemanticGraphCancellation()
        updates += refreshed.extracted.update
        coverage += refreshed.coverage
        omittedExternalTargetCount = Math.addExact(
            omittedExternalTargetCount,
            refreshed.extracted.omittedExternalTargetCount,
        )
    }

    checkSemanticGraphCancellation()
    val writeResult = if (updates.isNotEmpty() || removedPaths.isNotEmpty()) {
        val commitGraph = {
            store.replaceSemanticGraphFilesIfGeneration(
                expectedGeneration = scopeSnapshot.generation,
                updates = updates,
                removedPaths = removedPaths,
            )
        }
        val commit = runIdeaReadAction {
            val currentPsiGeneration = psiGeneration()
            if (snapshotPsiGeneration != currentPsiGeneration) {
                throw semanticGraphPsiGenerationConflict(snapshotPsiGeneration, currentPsiGeneration)
            }
            commitGraph()
        }
        when (commit) {
            is SemanticGraphCommitResult.Committed -> commit.writeResult
            is SemanticGraphCommitResult.GenerationChanged -> throw semanticGraphGenerationConflict(
                commit.expectedGeneration.value,
                commit.actualGeneration.value,
            )
        }
    } else {
        null
    }
    coverage += removedPaths.map { path ->
        SemanticGraphFileCoverage(path, null, SemanticGraphFileStatus.REMOVED)
    }
    return SemanticGraphResult(
        generation = SemanticGraphGeneration(writeResult?.generation?.value ?: store.readGeneration().value),
        scopeFingerprint = semanticGraphScopeFingerprint(selectedPaths, removedPaths),
        coverage = SemanticGraphCoverage(
            files = coverage.sortedBy(SemanticGraphFileCoverage::path),
            omittedExternalTargetCount = NonNegativeInt(omittedExternalTargetCount),
        ),
        symbolCount = NonNegativeInt(writeResult?.symbolCount ?: 0),
        edgeOccurrenceCount = NonNegativeInt(writeResult?.edgeOccurrenceCount ?: 0),
    )
}

private fun KastPluginBackend.refreshSemanticGraphFile(
    absolutePath: SemanticGraphPath,
    relativePath: SemanticGraphSourcePath,
    semanticScope: Set<SemanticGraphSourcePath>,
): RefreshedSemanticGraphFile {
    val file = findKtFile(absolutePath.value.value)
    val contentHash = sha256(file.text)
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
    return RefreshedSemanticGraphFile(
        extracted = extractSemanticGraphFile(file, relativePath, contentHash, evidence, semanticScope),
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

private fun semanticGraphScopeFingerprint(
    selectedPaths: List<SemanticGraphSourcePath>,
    removedPaths: List<SemanticGraphSourcePath>,
): SemanticGraphSha256 = sha256(
    buildString {
        selectedPaths.sorted().forEach { append("selected:").append(it.value).append('\n') }
        removedPaths.sorted().forEach { append("removed:").append(it.value).append('\n') }
    },
)
