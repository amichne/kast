package io.github.amichne.kast.idea.backend.semantic

import com.intellij.openapi.application.ApplicationManager
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
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.diagnostics.analyzeDiagnosticsFileInReadEpoch
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
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
        ApplicationManager.getApplication().runReadAction<SemanticGraphResult> {
            buildSemanticGraphSnapshot(query)
        }
    }

private fun KastPluginBackend.buildSemanticGraphSnapshot(query: ParsedSemanticGraphQuery): SemanticGraphResult {
    val store = requireNotNull(semanticGraphStore)
    val selectedPaths = query.filePaths.map(::toRelativeSemanticGraphPath)
    val removedPaths = query.removedFilePaths.map(::toRelativeSemanticGraphPath)
    val semanticScope = (store.semanticGraphSourcePaths() - removedPaths.toSet()) + selectedPaths
    val updates = mutableListOf<SemanticGraphFileIndexUpdate>()
    val coverage = mutableListOf<SemanticGraphFileCoverage>()
    var omittedExternalTargetCount = 0

    query.filePaths.zip(selectedPaths).forEach { (absolutePath, relativePath) ->
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
        val extracted = extractSemanticGraphFile(file, relativePath, contentHash, evidence, semanticScope)
        updates += extracted.update
        coverage += SemanticGraphFileCoverage(
            path = relativePath,
            contentHash = contentHash,
            status = SemanticGraphFileStatus.REFRESHED,
            diagnostics = evidence,
        )
        omittedExternalTargetCount = Math.addExact(
            omittedExternalTargetCount,
            extracted.omittedExternalTargetCount,
        )
    }

    val writeResult = if (updates.isNotEmpty() || removedPaths.isNotEmpty()) {
        store.replaceSemanticGraphFiles(updates, removedPaths)
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


private fun KastPluginBackend.toRelativeSemanticGraphPath(path: SemanticGraphPath): SemanticGraphSourcePath {
    val absolute = path.value.toJavaPath()
    require(absolute.startsWith(workspaceRoot)) {
        "Semantic graph path is outside the active workspace: ${path.value.value}"
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
