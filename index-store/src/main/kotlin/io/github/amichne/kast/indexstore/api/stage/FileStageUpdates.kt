package io.github.amichne.kast.indexstore.api.stage

import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
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

data class FileStageFailureUpdate(
    val work: PendingFileStage,
    val scannedContentHash: FileContentHash,
    val code: FileStageFailureCode,
    val message: String,
) {
    init {
        require(work.stage == FileIndexStage.RELATIONSHIPS) {
            "Relationship failure requires RELATIONSHIPS work"
        }
        require(scannedContentHash == work.contentHash) {
            "Failure content hash must match pending work"
        }
        require(message.isNotBlank() && message == message.trim() && message.none(Char::isISOControl)) {
            "Failure message must be non-blank, trimmed, and printable"
        }
        require(message.length <= 512) { "Failure message must be at most 512 characters" }
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

data class SemanticGraphFileStageFailureUpdate(
    val work: PendingFileStage,
    val scannedContentHash: FileContentHash,
    val sourcePath: SemanticGraphSourcePath,
    val code: FileStageFailureCode,
    val message: String,
) {
    init {
        require(work.stage == FileIndexStage.SEMANTIC_GRAPH) {
            "Semantic graph failure requires SEMANTIC_GRAPH work"
        }
        require(scannedContentHash == work.contentHash) {
            "Semantic graph failure content hash must match pending work"
        }
        require(message.isNotBlank() && message == message.trim() && message.none(Char::isISOControl)) {
            "Semantic graph failure message must be non-blank, trimmed, and printable"
        }
        require(message.length <= 512) { "Semantic graph failure message must be at most 512 characters" }
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
