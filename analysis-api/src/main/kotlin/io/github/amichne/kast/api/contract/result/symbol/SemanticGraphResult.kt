package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
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
    PROPERTY,
    GETTER,
    SETTER,
    VALUE_PARAMETER,
    RECEIVER_PARAMETER,
    TYPE_PARAMETER,
    TYPE_ALIAS,
}

@Serializable
enum class SemanticGraphVisibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    PRIVATE,
    LOCAL,
}

@Serializable
enum class SemanticGraphModality {
    FINAL,
    OPEN,
    ABSTRACT,
    SEALED,
}

@Serializable
enum class SemanticGraphOrigin {
    SOURCE,
    SYNTHETIC,
}

@Serializable
enum class SemanticGraphTypeKind {
    CLASS,
    TYPE_PARAMETER,
    FLEXIBLE,
    INTERSECTION,
    FUNCTION,
    SUSPEND_FUNCTION,
    ERROR,
    DYNAMIC,
    UNKNOWN,
}

@Serializable
enum class SemanticGraphTypeNullability {
    NON_NULL,
    NULLABLE,
    PLATFORM,
    UNKNOWN,
}

@Serializable
enum class SemanticGraphTypeRole {
    ARGUMENT,
    FLEXIBLE_LOWER,
    FLEXIBLE_UPPER,
    INTERSECTION_MEMBER,
    RECEIVER,
    RETURN,
    CONSTRAINT,
}

@Serializable
enum class SemanticGraphTypeVariance {
    INVARIANT,
    IN,
    OUT,
    STAR,
}

@Serializable
data class SemanticGraphTypeEdge(
    val childKey: NonBlankString? = null,
    val role: SemanticGraphTypeRole,
    val position: NonNegativeInt,
    val variance: SemanticGraphTypeVariance = SemanticGraphTypeVariance.INVARIANT,
)

@Serializable
data class SemanticGraphTypeFact(
    val stableKey: NonBlankString,
    val kind: SemanticGraphTypeKind,
    val classifier: NonBlankString? = null,
    val nullability: SemanticGraphTypeNullability,
    val debugText: NonBlankString,
    val edges: List<SemanticGraphTypeEdge> = emptyList(),
)

@Serializable
data class SemanticGraphSymbolFlags(
    val isExpect: Boolean = false,
    val isActual: Boolean = false,
    val isOverride: Boolean = false,
    val isSealed: Boolean = false,
    val isDelegated: Boolean = false,
)

@Serializable
enum class SemanticGraphRelationKind {
    CONTAINS,
    METHOD,
    CASE_OF,
    INHERITS,
    IMPLEMENTS,
    OVERRIDES,
    EXPECT_ACTUAL,
    SEALED_MEMBER,
    DELEGATES,
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
    RECEIVER_TYPE,
    TYPE_CONSTRAINT,
    ANNOTATION,
    DELEGATE,
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
    @DocField(description = "Compiler visibility retained for graph queries.")
    val visibility: SemanticGraphVisibility = SemanticGraphVisibility.PUBLIC,
    @DocField(description = "Compiler modality retained for graph queries.")
    val modality: SemanticGraphModality? = null,
    @DocField(description = "Whether the symbol comes from source or a compiler-synthesized declaration.")
    val origin: SemanticGraphOrigin = SemanticGraphOrigin.SOURCE,
    @DocField(description = "Graph-relevant compiler declaration flags.")
    val flags: SemanticGraphSymbolFlags = SemanticGraphSymbolFlags(),
    @DocField(description = "Fully-qualified annotation class names.")
    val annotations: List<NonBlankString> = emptyList(),
    @DocField(description = "Normalized declared-type identity.")
    val declaredTypeKey: NonBlankString? = null,
    @DocField(description = "Normalized receiver-type identity.")
    val receiverTypeKey: NonBlankString? = null,
    @DocField(description = "Normalized return-type identity.")
    val returnTypeKey: NonBlankString? = null,
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
    @DocField(description = "Shared source-index generation produced by this atomic refresh.")
    val generation: SemanticGraphGeneration,
    @DocField(description = "SHA-256 fingerprint of the selected and removed path scope.")
    val scopeFingerprint: SemanticGraphSha256,
    @DocField(description = "Refresh, diagnostic, and omission evidence for the scope.")
    val coverage: SemanticGraphCoverage,
    @DocField(description = "Number of canonical symbols written for selected files.")
    val symbolCount: NonNegativeInt,
    @DocField(description = "Number of typed edge occurrences written for selected files.")
    val edgeOccurrenceCount: NonNegativeInt,
)
