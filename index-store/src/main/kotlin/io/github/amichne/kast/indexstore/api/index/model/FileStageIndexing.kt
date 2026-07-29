package io.github.amichne.kast.indexstore.api.index

import java.util.UUID

@JvmInline
value class FileContentHash private constructor(val value: String) {
    companion object {
        fun parse(value: String): FileContentHash {
            val canonical = value.lowercase()
            require(canonical.length == 64 && canonical.all(Char::isHexDigit)) {
                "File content hash must be 64 hexadecimal characters"
            }
            return FileContentHash(canonical)
        }
    }
}

@JvmInline
value class FileStageInputFingerprint private constructor(val value: String) {
    companion object {
        fun parse(value: String): FileStageInputFingerprint =
            FileStageInputFingerprint(FileContentHash.parse(value).value)
    }
}

enum class FileIndexStage {
    SOURCE,
    RELATIONSHIPS,
    SEMANTIC_GRAPH,
}

@JvmInline
value class FileStageVersion private constructor(val value: String) {
    companion object {
        fun parse(value: String): FileStageVersion {
            require(value.isNotBlank() && value.trim() == value && value.none(Char::isISOControl)) {
                "File stage version must be non-blank, trimmed, and printable"
            }
            return FileStageVersion(value)
        }
    }
}

data class FileStageVersions(
    val source: FileStageVersion,
    val relationships: FileStageVersion,
    val semanticGraph: FileStageVersion,
) {
    operator fun get(stage: FileIndexStage): FileStageVersion = when (stage) {
        FileIndexStage.SOURCE -> source
        FileIndexStage.RELATIONSHIPS -> relationships
        FileIndexStage.SEMANTIC_GRAPH -> semanticGraph
    }

    companion object {
        val CURRENT = FileStageVersions(
            source = FileStageVersion.parse("source-1"),
            relationships = FileStageVersion.parse("relationships-1"),
            semanticGraph = FileStageVersion.parse("semantic-graph-1"),
        )
    }
}

data class FileInventoryEntry(
    val path: String,
    val lastModifiedMillis: Long,
    val contentHash: FileContentHash,
    val moduleName: String?,
    val sourceSet: String?,
) {
    init {
        require(path.isNotBlank()) { "File inventory path must be non-blank" }
        require(lastModifiedMillis >= 0) { "File inventory timestamp must be non-negative" }
        require(moduleName == null || moduleName.isNotBlank()) { "File inventory module must be non-blank" }
        require(sourceSet == null || sourceSet.isNotBlank()) { "File inventory source set must be non-blank" }
    }
}

enum class FileStageLimitation {
    UNRESOLVED_RELATIONSHIP,
}

@JvmInline
value class FileStageFailureId private constructor(val value: String) {
    companion object {
        fun create(): FileStageFailureId = FileStageFailureId(UUID.randomUUID().toString())

        fun parse(value: String): FileStageFailureId {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) { "File-stage failure ID must be a canonical UUID" }
            return FileStageFailureId(value)
        }
    }
}

enum class FileStageFailureCode {
    PSI_UNAVAILABLE,
}

data class FileStageFailure(
    val id: FileStageFailureId,
    val code: FileStageFailureCode,
    val message: String,
) {
    init {
        require(message.isNotBlank() && message == message.trim() && message.none(Char::isISOControl)) {
            "File-stage failure message must be non-blank, trimmed, and printable"
        }
        require(message.length <= 512) { "File-stage failure message must be at most 512 characters" }
    }
}

enum class FileStageFailureExternalizationResult {
    EXTERNALIZED,
    ALREADY_EXTERNAL,
    NOT_FOUND,
}

enum class FileStageOutcomeStatus {
    COMPLETE,
    LIMITED,
    FAILED,
    EXTERNAL_BOUNDARY,
}

data class PendingFileStage(
    val path: String,
    val contentHash: FileContentHash,
    val stage: FileIndexStage,
    val version: FileStageVersion,
    val inputFingerprint: FileStageInputFingerprint? = null,
) {
    init {
        require(path.isNotBlank()) { "Pending file-stage path must be non-blank" }
        require(stage == FileIndexStage.SEMANTIC_GRAPH || inputFingerprint == null) {
            "Only semantic graph work accepts an input fingerprint"
        }
    }
}

data class FileStageOutcome(
    val path: String,
    val contentHash: FileContentHash,
    val stage: FileIndexStage,
    val version: FileStageVersion,
    val status: FileStageOutcomeStatus,
    val limitations: List<FileStageLimitation>,
    val inputFingerprint: FileStageInputFingerprint? = null,
    val failure: FileStageFailure? = null,
) {
    init {
        require(path.isNotBlank()) { "File-stage outcome path must be non-blank" }
        require(stage == FileIndexStage.SEMANTIC_GRAPH || inputFingerprint == null) {
            "Only semantic graph outcomes accept an input fingerprint"
        }
        require((status == FileStageOutcomeStatus.LIMITED) == limitations.isNotEmpty()) {
            "Only limited file-stage outcomes carry limitations"
        }
        require(
            (status == FileStageOutcomeStatus.FAILED ||
                status == FileStageOutcomeStatus.EXTERNAL_BOUNDARY) == (failure != null),
        ) {
            "Failed and external-boundary outcomes require failure evidence"
        }
    }
}

sealed interface FileStageScopeCoverage {
    data class Complete(
        val totalFiles: Int,
    ) : FileStageScopeCoverage {
        init {
            require(totalFiles >= 0) { "Complete scope file count must be non-negative" }
        }
    }

    data class Limited(
        val totalFiles: Int,
        val completeFiles: Int,
        val pendingFiles: Int,
        val staleFiles: Int,
        val limitedFiles: Int,
        val failedFiles: Int,
        val limitations: List<FileStageLimitation>,
    ) : FileStageScopeCoverage {
        init {
            val counts = listOf(totalFiles, completeFiles, pendingFiles, staleFiles, limitedFiles, failedFiles)
            require(counts.all { it >= 0 }) { "Limited scope file counts must be non-negative" }
            require(completeFiles + pendingFiles + staleFiles + limitedFiles + failedFiles == totalFiles) {
                "Limited scope outcome counts must equal total files"
            }
        }
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f'
