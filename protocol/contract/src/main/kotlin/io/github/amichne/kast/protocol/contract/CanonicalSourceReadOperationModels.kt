@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64

private const val MAX_SOURCE_READ_LINE_COUNT = 1_000
private const val MAX_SOURCE_READ_ENTITY_LIMIT = 1_000
private const val MAX_SOURCE_READ_TEXT_LENGTH = 1_048_576

enum class SourceReadAnchorDocumentFailure {
    UNKNOWN_TOKEN_FAMILY,
    INVALID_TOKEN_STRUCTURE,
    INVALID_PAYLOAD_ENCODING,
    PAYLOAD_DIGEST_MISMATCH,
}

@Serializable
sealed interface SourceReadAnchorDocument {
    @Serializable
    @SerialName("candidate")
    data class Candidate(val selector: ProtocolText) : SourceReadAnchorDocument
    @Serializable
    @SerialName("symbol")
    data class Symbol(val selector: ProtocolText) : SourceReadAnchorDocument
    @Serializable
    @SerialName("source")
    data class Source(val selector: ProtocolText) : SourceReadAnchorDocument

    companion object {
        /** Refines one opaque selector to its sole disjoint anchor family. */
        fun admit(
            selector: ProtocolText,
        ): Refinement<SourceReadAnchorDocument, SourceReadAnchorDocumentFailure> {
            val parts = selector.value.split(':')
            val family = when {
                parts.size == 4 && parts[0] == "candidate" && parts[1] == "v2" ->
                    SourceReadAnchorFamily.CANDIDATE
                parts.size == 4 && parts[0] == "exact" && parts[1] == "v2" ->
                    SourceReadAnchorFamily.SYMBOL
                parts.size == 3 && parts[0] == "source-selector-v1" ->
                    SourceReadAnchorFamily.SOURCE
                parts.firstOrNull() in setOf("candidate", "exact", "source-selector-v1") ->
                    return Refinement.Rejected(
                        SourceReadAnchorDocumentFailure.INVALID_TOKEN_STRUCTURE,
                    )
                else -> return Refinement.Rejected(
                    SourceReadAnchorDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                )
            }
            val payloadIndex = if (family == SourceReadAnchorFamily.SOURCE) 1 else 2
            val digestIndex = payloadIndex + 1
            val payload = try {
                Base64.getUrlDecoder().decode(parts[payloadIndex])
            } catch (_: IllegalArgumentException) {
                return Refinement.Rejected(
                    SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING,
                )
            }
            if (
                payload.isEmpty() ||
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload) != parts[payloadIndex]
            ) {
                return Refinement.Rejected(
                    SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING,
                )
            }
            try {
                payload.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return Refinement.Rejected(
                    SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING,
                )
            }
            if (parts[digestIndex] != sourceReadSha256(payload)) {
                return Refinement.Rejected(
                    SourceReadAnchorDocumentFailure.PAYLOAD_DIGEST_MISMATCH,
                )
            }
            return Refinement.Refined(
                when (family) {
                    SourceReadAnchorFamily.CANDIDATE -> Candidate(selector)
                    SourceReadAnchorFamily.SYMBOL -> Symbol(selector)
                    SourceReadAnchorFamily.SOURCE -> Source(selector)
                },
            )
        }
    }
}

private enum class SourceReadAnchorFamily { CANDIDATE, SYMBOL, SOURCE }

private fun sourceReadSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

@Serializable
enum class SourceBodyKindDocument {
    @SerialName("callable")
    CALLABLE,
    @SerialName("class")
    CLASS,
}

@Serializable
enum class SourceEnclosingRegionKindDocument {
    @SerialName("declaration")
    DECLARATION,
    @SerialName("callable-body")
    CALLABLE_BODY,
    @SerialName("class-body")
    CLASS_BODY,
}

@Serializable
sealed interface SourceRegionSelectionDocument {
    @Serializable
    @SerialName("anchor")
    data object Anchor : SourceRegionSelectionDocument
    @Serializable
    @SerialName("body")
    data class Body(val kind: SourceBodyKindDocument) : SourceRegionSelectionDocument
    @Serializable
    @SerialName("file")
    data object File : SourceRegionSelectionDocument
    @Serializable
    @SerialName("enclosing")
    data class Enclosing(
        val kind: SourceEnclosingRegionKindDocument,
    ) : SourceRegionSelectionDocument
}

@Serializable
enum class SourceDeclarationKindDocument {
    @SerialName("classlike")
    CLASSLIKE,
    @SerialName("constructor")
    CONSTRUCTOR,
    @SerialName("function")
    FUNCTION,
    @SerialName("property")
    PROPERTY,
    @SerialName("type-alias")
    TYPE_ALIAS,
}

@Serializable
enum class SourceDeclarationVisibilityDocument {
    @SerialName("public")
    PUBLIC,
    @SerialName("protected")
    PROTECTED,
    @SerialName("internal")
    INTERNAL,
    @SerialName("private")
    PRIVATE,
    @SerialName("local")
    LOCAL,
}

@Serializable
enum class SourceContainmentDocument {
    @SerialName("direct")
    DIRECT,
    @SerialName("descendants")
    DESCENDANTS,
}

@Serializable
sealed interface SourceVisibilitySelectionDocument {
    @Serializable
    @SerialName("any")
    data object Any : SourceVisibilitySelectionDocument
    @Serializable
    @SerialName("exact")
    data class Exact(
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val values: List<SourceDeclarationVisibilityDocument>,
    ) :
        SourceVisibilitySelectionDocument
}

@Serializable
sealed interface SourceEntityFilterDocument {
    @Serializable
    @SerialName("declaration")
    data class Declarations(
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val kinds: List<SourceDeclarationKindDocument>,
        val visibility: SourceVisibilitySelectionDocument,
    ) : SourceEntityFilterDocument

    @Serializable
    @SerialName("parameters")
    data object Parameters : SourceEntityFilterDocument
    @Serializable
    @SerialName("calls")
    data object Calls : SourceEntityFilterDocument
    @Serializable
    @SerialName("references")
    data object References : SourceEntityFilterDocument
}

@Serializable
sealed interface SourceEntitySelectionDocument {
    @Serializable
    @SerialName("none")
    data object None : SourceEntitySelectionDocument
    @Serializable
    @SerialName("matching")
    data class Matching(
        val containment: SourceContainmentDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val filters: List<SourceEntityFilterDocument>,
    ) : SourceEntitySelectionDocument
}

enum class SourceLineCountDocumentFailure {
    NEGATIVE,
    TOO_LARGE,
}

@JvmInline
@Serializable(with = SourceLineCountDocumentSerializer::class)
value class SourceLineCountDocument private constructor(val value: Int) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<SourceLineCountDocument, SourceLineCountDocumentFailure> = when {
            raw < 0 -> Refinement.Rejected(SourceLineCountDocumentFailure.NEGATIVE)
            raw > MAX_SOURCE_READ_LINE_COUNT ->
                Refinement.Rejected(SourceLineCountDocumentFailure.TOO_LARGE)
            else -> Refinement.Refined(SourceLineCountDocument(raw))
        }
    }
}

internal object SourceLineCountDocumentSerializer : RefiningIntSerializer<SourceLineCountDocument>(
    serialName = "io.github.amichne.kast.protocol.contract.SourceLineCountDocument",
    minimum = 0,
    maximum = MAX_SOURCE_READ_LINE_COUNT.toLong(),
) {
    override fun raw(value: SourceLineCountDocument): Int = value.value

    override fun refine(raw: Int): Refinement<SourceLineCountDocument, *> =
        SourceLineCountDocument.parse(raw)
}

@Serializable
sealed interface SourceTextRequestDocument {
    @Serializable
    @SerialName("complete")
    data object Complete : SourceTextRequestDocument
    @Serializable
    @SerialName("none")
    data object None : SourceTextRequestDocument
    @Serializable
    @SerialName("window")
    data class Window(
        val beforeLines: SourceLineCountDocument,
        val afterLines: SourceLineCountDocument,
    ) : SourceTextRequestDocument
}

enum class SourceEntityLimitDocumentFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

@JvmInline
@Serializable(with = SourceEntityLimitDocumentSerializer::class)
value class SourceEntityLimitDocument private constructor(val value: Int) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<SourceEntityLimitDocument, SourceEntityLimitDocumentFailure> = when {
            raw < 1 -> Refinement.Rejected(SourceEntityLimitDocumentFailure.NOT_POSITIVE)
            raw > MAX_SOURCE_READ_ENTITY_LIMIT ->
                Refinement.Rejected(SourceEntityLimitDocumentFailure.TOO_LARGE)
            else -> Refinement.Refined(SourceEntityLimitDocument(raw))
        }
    }
}

internal object SourceEntityLimitDocumentSerializer : RefiningIntSerializer<SourceEntityLimitDocument>(
    serialName = "io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument",
    minimum = 1,
    maximum = MAX_SOURCE_READ_ENTITY_LIMIT.toLong(),
) {
    override fun raw(value: SourceEntityLimitDocument): Int = value.value

    override fun refine(raw: Int): Refinement<SourceEntityLimitDocument, *> =
        SourceEntityLimitDocument.parse(raw)
}

enum class SourceTextByteLimitDocumentFailure {
    NOT_POSITIVE,
}

@JvmInline
@Serializable(with = SourceTextByteLimitDocumentSerializer::class)
value class SourceTextByteLimitDocument private constructor(val value: Long) {
    companion object {
        fun parse(
            raw: Long,
        ): Refinement<SourceTextByteLimitDocument, SourceTextByteLimitDocumentFailure> =
            if (raw < 1L) {
                Refinement.Rejected(SourceTextByteLimitDocumentFailure.NOT_POSITIVE)
            } else {
                Refinement.Refined(SourceTextByteLimitDocument(raw))
            }
    }
}

internal object SourceTextByteLimitDocumentSerializer : RefiningLongSerializer<SourceTextByteLimitDocument>(
    serialName = "io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument",
    minimum = 1,
) {
    override fun raw(value: SourceTextByteLimitDocument): Long = value.value

    override fun refine(raw: Long): Refinement<SourceTextByteLimitDocument, *> =
        SourceTextByteLimitDocument.parse(raw)
}

@Serializable
sealed interface SourceReadPageDocument {
    @Serializable
    @SerialName("first")
    data object First : SourceReadPageDocument
    @Serializable
    @SerialName("continue")
    data class Continue(val continuation: ProtocolText) : SourceReadPageDocument
}

@Serializable(with = SourceReadRequestSerializer::class)
@KeepGeneratedSerializer
data class SourceReadRequest(
    val anchor: SourceReadAnchorDocument,
    val region: SourceRegionSelectionDocument,
    val entities: SourceEntitySelectionDocument,
    val text: SourceTextRequestDocument,
    val entityLimit: SourceEntityLimitDocument,
    val textByteLimit: SourceTextByteLimitDocument,
    val page: SourceReadPageDocument,
) : OperationRequest

internal object SourceReadRequestSerializer : KSerializer<SourceReadRequest> {
    private val delegate = SourceReadRequest.generatedSerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: SourceReadRequest) {
        delegate.serialize(encoder, value.requireCanonicalSyntax())
    }

    override fun deserialize(decoder: Decoder): SourceReadRequest =
        delegate.deserialize(decoder).requireCanonicalSyntax()
}

private fun SourceReadRequest.requireCanonicalSyntax(): SourceReadRequest =
    if (anchor.hasCanonicalSyntax() && entities.hasCanonicalSyntax()) this
    else throw SerializationException("SourceReadRequest rejected non-canonical request syntax")

private fun SourceReadAnchorDocument.hasCanonicalSyntax(): Boolean {
    val selector = when (this) {
        is SourceReadAnchorDocument.Candidate -> selector
        is SourceReadAnchorDocument.Symbol -> selector
        is SourceReadAnchorDocument.Source -> selector
    }
    return when (val admitted = SourceReadAnchorDocument.admit(selector)) {
        is Refinement.Refined -> admitted.value::class == this::class
        is Refinement.Rejected -> false
    }
}

private fun SourceEntitySelectionDocument.hasCanonicalSyntax(): Boolean = when (this) {
    SourceEntitySelectionDocument.None -> true
    is SourceEntitySelectionDocument.Matching -> filters.hasCanonicalSyntax()
}

private fun List<SourceEntityFilterDocument>.hasCanonicalSyntax(): Boolean {
    if (isEmpty()) return false
    val keys = map { filter ->
        when (filter) {
            is SourceEntityFilterDocument.Declarations -> {
                if (!filter.hasCanonicalSyntax()) return false
                0
            }
            SourceEntityFilterDocument.Parameters -> 1
            SourceEntityFilterDocument.Calls -> 2
            SourceEntityFilterDocument.References -> 3
        }
    }
    return keys == keys.distinct().sorted()
}

private fun SourceEntityFilterDocument.Declarations.hasCanonicalSyntax(): Boolean =
    kinds.isNotEmpty() && kinds == kinds.distinct().sortedBy { it.ordinal } &&
        visibility.hasCanonicalSyntax()

private fun SourceVisibilitySelectionDocument.hasCanonicalSyntax(): Boolean = when (this) {
    SourceVisibilitySelectionDocument.Any -> true
    is SourceVisibilitySelectionDocument.Exact ->
        values.isNotEmpty() && values == values.distinct().sortedBy { it.ordinal }
}

enum class SourceCoordinateUnitDocument {
    UTF16_CODE_UNIT,
}

enum class SourceLengthDocumentFailure {
    NEGATIVE,
}

@JvmInline
value class SourceLengthDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<SourceLengthDocument, SourceLengthDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(SourceLengthDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(SourceLengthDocument(raw))
            }
    }
}

data class SourceSnapshotDocument(
    val canonicalRoot: ProtocolText,
    val generation: Long,
    val sourceState: ProtocolText,
    val file: ProtocolText,
    val textIdentity: ProtocolText,
    val coordinateUnit: SourceCoordinateUnitDocument,
    val length: SourceLengthDocument,
)

enum class SourceSelectionRangeDocumentFailure {
    REVERSED,
}

@ConsistentCopyVisibility
data class SourceSelectionRangeDocument private constructor(
    val startInclusive: ProtocolOffset,
    val endExclusive: ProtocolOffset,
) {
    companion object {
        fun create(
            startInclusive: ProtocolOffset,
            endExclusive: ProtocolOffset,
        ): Refinement<SourceSelectionRangeDocument, SourceSelectionRangeDocumentFailure> =
            if (endExclusive.value < startInclusive.value) {
                Refinement.Rejected(SourceSelectionRangeDocumentFailure.REVERSED)
            } else {
                Refinement.Refined(SourceSelectionRangeDocument(startInclusive, endExclusive))
            }
    }
}

data class SourceSelectionDocument(
    val selector: ProtocolText,
    val range: SourceSelectionRangeDocument,
)

enum class SourceRegionKindDocument {
    ANCHOR,
    DECLARATION,
    CALLABLE_BODY,
    CLASS_BODY,
    FILE,
    WINDOW,
}

data class SourceRegionDocument(
    val kind: SourceRegionKindDocument,
    val selection: SourceSelectionDocument,
)

enum class SourceNestingDepthDocumentFailure {
    NEGATIVE,
}

@JvmInline
value class SourceNestingDepthDocument private constructor(val value: Int) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<SourceNestingDepthDocument, SourceNestingDepthDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(SourceNestingDepthDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(SourceNestingDepthDocument(raw))
            }
    }
}

sealed interface SourceDeclarationSemanticIdentityDocument {
    data class Candidate(val selector: ProtocolText) : SourceDeclarationSemanticIdentityDocument
}

enum class SourceUnresolvedReasonDocument {
    NAME_NOT_FOUND,
    AMBIGUOUS,
    ERROR_TYPE,
    UNSUPPORTED_TARGET,
}

sealed interface SourceEntityTargetDocument {
    data class Candidate(val selector: ProtocolText) : SourceEntityTargetDocument
    data class Local(val selector: ProtocolText) : SourceEntityTargetDocument
    data class Unresolved(val reason: SourceUnresolvedReasonDocument) : SourceEntityTargetDocument
}

sealed interface SourceEntityDocument {
    val selection: SourceSelectionDocument
    val parentSelector: ProtocolText
    val nestingDepth: SourceNestingDepthDocument

    data class Declaration(
        val kind: SourceDeclarationKindDocument,
        val name: ProtocolText,
        val visibility: SourceDeclarationVisibilityDocument,
        override val nestingDepth: SourceNestingDepthDocument,
        override val parentSelector: ProtocolText,
        override val selection: SourceSelectionDocument,
        val semanticIdentity: SourceDeclarationSemanticIdentityDocument,
    ) : SourceEntityDocument

    data class ValueParameter(
        val name: ProtocolText,
        override val nestingDepth: SourceNestingDepthDocument,
        override val parentSelector: ProtocolText,
        override val selection: SourceSelectionDocument,
    ) : SourceEntityDocument

    data class Call(
        override val nestingDepth: SourceNestingDepthDocument,
        override val parentSelector: ProtocolText,
        override val selection: SourceSelectionDocument,
        val callee: SourceSelectionDocument,
        val target: SourceEntityTargetDocument,
    ) : SourceEntityDocument

    data class Reference(
        val name: ProtocolText,
        override val nestingDepth: SourceNestingDepthDocument,
        override val parentSelector: ProtocolText,
        override val selection: SourceSelectionDocument,
        val target: SourceEntityTargetDocument,
    ) : SourceEntityDocument
}

enum class ProtocolSourceTextFailure {
    TOO_LONG,
    NOT_NORMALIZED,
}

/** Bounded normalized source text; unlike ProtocolText, an empty file is valid. */
@JvmInline
value class ProtocolSourceText private constructor(val value: String) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<ProtocolSourceText, ProtocolSourceTextFailure> = when {
            raw.length > MAX_SOURCE_READ_TEXT_LENGTH ->
                Refinement.Rejected(ProtocolSourceTextFailure.TOO_LONG)
            '\r' in raw -> Refinement.Rejected(ProtocolSourceTextFailure.NOT_NORMALIZED)
            else -> Refinement.Refined(ProtocolSourceText(raw))
        }
    }
}

enum class SourceTextWithheldReasonDocument {
    BYTE_LIMIT_REACHED,
    PROVIDER_UNAVAILABLE,
}

sealed interface SourceTextProjectionDocument {
    data object NotRequested : SourceTextProjectionDocument
    data class Returned(
        val selection: SourceSelectionDocument,
        val text: ProtocolSourceText,
        val lines: SourceLineRangeDocument,
    ) : SourceTextProjectionDocument
    data class Withheld(
        val reason: SourceTextWithheldReasonDocument,
    ) : SourceTextProjectionDocument
}

data class SourceReadResult(
    val snapshot: SourceSnapshotDocument,
    val region: SourceRegionDocument,
    val entities: BoundedProtocolList<SourceEntityDocument>,
    val text: SourceTextProjectionDocument,
) : OperationResult

enum class SourceReadLimitationDocument {
    ENTITY_LIMIT_REACHED,
    TEXT_BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    SEMANTIC_RESOLUTION_INCOMPLETE,
    UNSUPPORTED_ENTITY,
    PROVIDER_FAILURE,
}

enum class SourceEntityCountDocumentFailure {
    NEGATIVE,
}

@JvmInline
value class SourceEntityCountDocument private constructor(val value: Int) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<SourceEntityCountDocument, SourceEntityCountDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(SourceEntityCountDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(SourceEntityCountDocument(raw))
            }
    }
}

sealed interface SourceReadContinuationStateDocument {
    data object Unavailable : SourceReadContinuationStateDocument
    data class Available(val continuation: ProtocolText) : SourceReadContinuationStateDocument
}

enum class SourceReadQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
    CONTINUATION_REQUIRED,
}

@ConsistentCopyVisibility
data class SourceReadQualification private constructor(
    val knownMinimumEntityCount: SourceEntityCountDocument,
    val limitations: List<SourceReadLimitationDocument>,
    val continuation: SourceReadContinuationStateDocument,
) : OperationQualification {
    companion object {
        fun create(
            knownMinimumEntityCount: SourceEntityCountDocument,
            limitations: List<SourceReadLimitationDocument>,
            continuation: SourceReadContinuationStateDocument,
        ): Refinement<SourceReadQualification, SourceReadQualificationFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(SourceReadQualificationFailure.EMPTY_LIMITATIONS)
            }
            if (limitations != limitations.distinct().sortedBy { it.ordinal }) {
                return Refinement.Rejected(
                    SourceReadQualificationFailure.NON_CANONICAL_LIMITATIONS,
                )
            }
            if (
                SourceReadLimitationDocument.ENTITY_LIMIT_REACHED in limitations &&
                continuation is SourceReadContinuationStateDocument.Unavailable
            ) {
                return Refinement.Rejected(SourceReadQualificationFailure.CONTINUATION_REQUIRED)
            }
            return Refinement.Refined(
                SourceReadQualification(knownMinimumEntityCount, limitations, continuation),
            )
        }
    }
}

enum class SourceReadRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SOURCE_STATE_MISMATCH,
    CANDIDATE_STALE,
    SOURCE_SELECTOR_STALE,
    SOURCE_SNAPSHOT_MISMATCH,
    SOURCE_UNAVAILABLE,
    DOCUMENT_DIRTY,
    PSI_DOCUMENT_UNCOMMITTED,
    OUTSIDE_SOURCE_SCOPE,
    ANCHOR_NOT_FOUND,
    AMBIGUOUS_ANCHOR,
    REGION_NOT_APPLICABLE,
    REGION_ABSENT,
    COMPILER_ANALYSIS_UNAVAILABLE,
    CONTRACT_VIOLATION,
}
