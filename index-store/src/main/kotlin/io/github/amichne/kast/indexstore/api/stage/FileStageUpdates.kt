package io.github.amichne.kast.indexstore.api.stage

import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow

data class SourceFileStageUpdate(
    val work: PendingFileStage,
    val scannedContentHash: FileContentHash,
    val update: FileIndexUpdate,
    val limitations: List<FileStageLimitation> = emptyList(),
) {
    init {
        require(work.stage == FileIndexStage.SOURCE) { "Source update requires SOURCE work" }
        require(scannedContentHash == work.contentHash) {
            "Source update content hash must match pending work"
        }
        require(update.path == work.path) { "Source update path must match pending work" }
    }
}

data class RelationshipFileStageUpdate(
    val work: PendingFileStage,
    val scannedContentHash: FileContentHash,
    val references: List<SymbolReferenceRow>,
    val declarations: List<DeclarationRow>,
    val limitations: List<FileStageLimitation> = emptyList(),
) {
    init {
        require(work.stage == FileIndexStage.RELATIONSHIPS) {
            "Relationship update requires RELATIONSHIPS work"
        }
        require(scannedContentHash == work.contentHash) {
            "Relationship update content hash must match pending work"
        }
        require(references.all { reference -> reference.sourcePath == work.path }) {
            "Every relationship must originate in the pending file"
        }
        require(declarations.all { declaration -> declaration.filePath == work.path }) {
            "Every declaration must originate in the pending file"
        }
    }
}

data class SemanticGraphFileStageUpdate(
    val work: PendingFileStage,
    val update: SemanticGraphFileIndexUpdate,
    val limitations: List<FileStageLimitation> = emptyList(),
) {
    init {
        require(work.stage == FileIndexStage.SEMANTIC_GRAPH) {
            "Semantic graph update requires SEMANTIC_GRAPH work"
        }
        require(update.contentHash.value == work.contentHash.value) {
            "Semantic graph update content hash must match pending work"
        }
    }
}

data class SemanticGraphFileStageRemoval(
    val outcomePath: String,
    val sourcePath: SemanticGraphSourcePath,
) {
    init {
        require(outcomePath.isNotBlank()) { "Semantic graph outcome path must be non-blank" }
    }
}
