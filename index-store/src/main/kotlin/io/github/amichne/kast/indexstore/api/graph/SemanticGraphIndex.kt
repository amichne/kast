package io.github.amichne.kast.indexstore.api.graph

import io.github.amichne.kast.api.contract.result.SemanticGraphDiagnosticEvidence
import io.github.amichne.kast.api.contract.result.SemanticGraphFileCoverage
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint

data class SemanticGraphFileIndexUpdate(
    val path: SemanticGraphSourcePath,
    val contentHash: SemanticGraphSha256,
    val status: SemanticGraphFileStatus,
    val diagnostics: List<SemanticGraphDiagnosticEvidence>,
    val configurationFingerprint: BuildClasspathFingerprint?,
    val omittedExternalTargetCount: NonNegativeInt,
    val dependencyContentHashes: Map<SemanticGraphSourcePath, SemanticGraphSha256>,
    val symbols: List<SemanticGraphSymbol>,
    val relations: List<SemanticGraphRelation>,
) {
    init {
        require(symbols.all { symbol -> symbol.path == path }) {
            "Every semantic graph symbol must belong to the replaced file"
        }
        require(relations.all { relation -> relation.sourcePath == path }) {
            "Every semantic graph relation must originate in the replaced file"
        }
    }
}

data class SemanticGraphFileIndexRecord(
    val coverage: SemanticGraphFileCoverage,
    val configurationFingerprint: BuildClasspathFingerprint?,
    val omittedExternalTargetCount: NonNegativeInt,
    val dependencyContentHashes: Map<SemanticGraphSourcePath, SemanticGraphSha256>,
)

data class SemanticGraphIndexSnapshot(
    val generation: SourceIndexGeneration,
    val fileRecords: List<SemanticGraphFileIndexRecord>,
    val symbols: List<SemanticGraphSymbol>,
    val boundarySymbols: List<SemanticGraphSymbol>,
    val relations: List<SemanticGraphRelation>,
) {
    val files: List<SemanticGraphFileCoverage>
        get() = fileRecords.map(SemanticGraphFileIndexRecord::coverage)

    val omittedExternalTargetCount: NonNegativeInt
        get() = NonNegativeInt(
            fileRecords.fold(0) { total, record ->
                Math.addExact(total, record.omittedExternalTargetCount.value)
            },
        )
}
