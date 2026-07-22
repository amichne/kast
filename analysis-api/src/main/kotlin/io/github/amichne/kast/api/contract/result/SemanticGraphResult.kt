package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.query.SemanticGraphPageToken
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SemanticGraphSymbolKey private constructor(
    @DocField(description = "Compiler-derived canonical semantic symbol identity.")
    val value: String,
) : Comparable<SemanticGraphSymbolKey> {
    init {
        require(value.isNotBlank()) { "Semantic graph symbol key must not be blank" }
    }

    companion object {
        fun parse(raw: String): SemanticGraphSymbolKey = SemanticGraphSymbolKey(raw)
    }

    override fun compareTo(other: SemanticGraphSymbolKey): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class SemanticGraphSourcePath private constructor(
    @DocField(description = "Normalized repository-relative Kotlin source path.")
    val value: String,
) : Comparable<SemanticGraphSourcePath> {
    init {
        require(value.isNotBlank()) { "Semantic graph source path must not be blank" }
        require(value.none(Char::isISOControl)) { "Semantic graph source path must not contain control characters" }
        require('\\' !in value) { "Semantic graph source path must use forward slashes" }
        require(!value.startsWith('/')) { "Semantic graph source path must be relative" }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(value)) {
            "Semantic graph source path must not use a Windows drive prefix"
        }
        require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Semantic graph source path must be normalized and contained"
        }
        require(value.endsWith(".kt") || value.endsWith(".kts")) {
            "Semantic graph source path must identify a Kotlin source file"
        }
    }

    companion object {
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")

        fun parse(raw: String): SemanticGraphSourcePath =
            SemanticGraphSourcePath(raw.replace('\\', '/'))
    }

    override fun compareTo(other: SemanticGraphSourcePath): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class SemanticGraphSha256 private constructor(
    @DocField(description = "Lowercase SHA-256 digest.")
    val value: String,
) {
    init {
        require(value.length == SHA_256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Semantic graph digest must be lowercase SHA-256 hex"
        }
    }

    companion object {
        private const val SHA_256_HEX_LENGTH = 64

        fun parse(raw: String): SemanticGraphSha256 = SemanticGraphSha256(raw)
    }
}

@Serializable
@JvmInline
value class SemanticGraphGeneration(
    @DocField(description = "Shared source-index generation for this graph snapshot.")
    val value: Long,
) {
    init {
        require(value >= 0) { "Semantic graph generation must be non-negative" }
    }
}

@Serializable
enum class SemanticGraphSymbolKind {
    FILE,
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ENUM_ENTRY,
    FUNCTION,
    MEMBER_FUNCTION,
    CONSTRUCTOR,
}

@Serializable
enum class SemanticGraphRelationKind {
    CONTAINS,
    METHOD,
    CASE_OF,
    INHERITS,
    IMPLEMENTS,
    CALLS,
    REFERENCES,
}

@Serializable
enum class SemanticGraphRelationContext {
    NONE,
    FIELD,
    PARAMETER_TYPE,
    RETURN_TYPE,
    GENERIC_ARG,
    CALL,
}

@Serializable
enum class SemanticGraphFileStatus {
    REFRESHED,
    CACHED,
    REMOVED,
}

@Serializable
data class SemanticGraphSymbol(
    @DocField(description = "Canonical overload-safe symbol identity.")
    val canonicalKey: SemanticGraphSymbolKey,
    @DocField(description = "Projected Kotlin declaration kind.")
    val kind: SemanticGraphSymbolKind,
    @DocField(description = "Simple source-level declaration name.")
    val name: NonBlankString,
    @DocField(description = "Compiler-resolved fully-qualified name when available.")
    val fqName: FqName? = null,
    @DocField(description = "Compiler-derived callable signature when applicable.")
    val signature: NonBlankString? = null,
    @DocField(description = "Canonical key of the nearest projected owner.")
    val ownerKey: SemanticGraphSymbolKey? = null,
    @DocField(description = "Repository-relative Kotlin source path.")
    val path: SemanticGraphSourcePath,
    @DocField(description = "Exact zero-based declaration start offset.")
    val startOffset: ByteOffset,
    @DocField(description = "Exact zero-based declaration end offset.")
    val endOffset: ByteOffset,
    @DocField(description = "One-based declaration line.")
    val line: LineNumber,
) {
    init {
        require(endOffset >= startOffset) { "Semantic graph symbol range is invalid" }
    }
}

@Serializable
data class SemanticGraphRelation(
    @DocField(description = "Canonical key of the nearest projected source declaration.")
    val sourceKey: SemanticGraphSymbolKey,
    @DocField(description = "Canonical key of the projected relation target.")
    val targetKey: SemanticGraphSymbolKey,
    @DocField(description = "Exact compiler target when projection intentionally redirects the public endpoint.")
    val resolvedTargetKey: SemanticGraphSymbolKey? = null,
    @DocField(description = "Semantic or structural relation kind.")
    val kind: SemanticGraphRelationKind,
    @DocField(description = "Source construct context for the relation.")
    val context: SemanticGraphRelationContext = SemanticGraphRelationContext.NONE,
    @DocField(description = "Repository-relative source path containing the occurrence.")
    val sourcePath: SemanticGraphSourcePath,
    @DocField(description = "Exact zero-based occurrence start offset.")
    val startOffset: ByteOffset,
    @DocField(description = "Exact zero-based occurrence end offset.")
    val endOffset: ByteOffset,
    @DocField(description = "One-based occurrence line.")
    val line: LineNumber,
) {
    init {
        require(endOffset >= startOffset) { "Semantic graph relation range is invalid" }
    }
}

@Serializable
data class SemanticGraphDiagnosticEvidence(
    @DocField(description = "Compiler diagnostic severity.")
    val severity: DiagnosticSeverity,
    @DocField(description = "Compiler diagnostic message.")
    val message: NonBlankString,
    @DocField(description = "Exact zero-based diagnostic start offset.")
    val startOffset: ByteOffset,
    @DocField(description = "Exact zero-based diagnostic end offset.")
    val endOffset: ByteOffset,
    @DocField(description = "One-based diagnostic line.")
    val line: LineNumber,
) {
    init {
        require(endOffset >= startOffset) { "Semantic graph diagnostic range is invalid" }
    }
}

@Serializable
data class SemanticGraphFileCoverage(
    @DocField(description = "Repository-relative Kotlin source path.")
    val path: SemanticGraphSourcePath,
    @DocField(description = "Content hash for refreshed or cached files.")
    val contentHash: SemanticGraphSha256?,
    @DocField(description = "Refresh outcome for this file.")
    val status: SemanticGraphFileStatus,
    @DocField(description = "Compiler diagnostics observed while refreshing the file.")
    val diagnostics: List<SemanticGraphDiagnosticEvidence> = emptyList(),
)

@Serializable
data class SemanticGraphCoverage(
    @DocField(description = "Per-file refresh and diagnostic evidence.")
    val files: List<SemanticGraphFileCoverage>,
    @DocField(description = "Resolved library or JDK targets intentionally omitted from the workspace graph.")
    val omittedExternalTargetCount: NonNegativeInt = NonNegativeInt(0),
)

@Serializable
data class SemanticGraphResult(
    @DocField(description = "Shared source-index generation bound to this page sequence.")
    val generation: SemanticGraphGeneration,
    @DocField(description = "SHA-256 fingerprint of the selected and removed path scope.")
    val scopeFingerprint: SemanticGraphSha256,
    @DocField(description = "Refresh, diagnostic, and omission evidence for the scope.")
    val coverage: SemanticGraphCoverage,
    @DocField(description = "Semantic symbol records included in this page.")
    val symbols: List<SemanticGraphSymbol>,
    @DocField(description = "Referenced workspace symbols outside the selected file scope, returned without expansion.")
    val boundarySymbols: List<SemanticGraphSymbol> = emptyList(),
    @DocField(description = "Semantic relation records included in this page.")
    val relations: List<SemanticGraphRelation>,
    @DocField(description = "Opaque token for the next page, or null when complete.")
    val nextPageToken: SemanticGraphPageToken? = null,
)
