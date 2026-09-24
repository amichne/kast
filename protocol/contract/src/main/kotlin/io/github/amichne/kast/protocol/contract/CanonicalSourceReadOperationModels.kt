@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal const val MAX_SOURCE_READ_LINE_COUNT = 1_000
internal const val MAX_SOURCE_READ_ENTITY_LIMIT = 1_000
private const val MAX_SOURCE_READ_TEXT_LENGTH = 1_048_576

@Serializable
enum class SourceBodyKindDocument {
    @SerialName("callable") CALLABLE,
    @SerialName("class") CLASS,
}

@Serializable
enum class SourceEnclosingRegionKindDocument {
    @SerialName("declaration") DECLARATION,
    @SerialName("callable-body") CALLABLE_BODY,
    @SerialName("class-body") CLASS_BODY,
}

@Serializable
sealed interface SourceRegionSelectionDocument {
    @Serializable @SerialName("anchor") data object Anchor : SourceRegionSelectionDocument

    @Serializable @SerialName("body") data class Body(val kind: SourceBodyKindDocument) : SourceRegionSelectionDocument

    @Serializable @SerialName("file") data object File : SourceRegionSelectionDocument

    @Serializable
    @SerialName("enclosing")
    data class Enclosing(val kind: SourceEnclosingRegionKindDocument) : SourceRegionSelectionDocument
}

@Serializable
enum class SourceDeclarationKindDocument {
    @SerialName("classlike") CLASSLIKE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}

@Serializable
enum class SourceDeclarationVisibilityDocument {
    @SerialName("public") PUBLIC,
    @SerialName("protected") PROTECTED,
    @SerialName("internal") INTERNAL,
    @SerialName("private") PRIVATE,
    @SerialName("local") LOCAL,
}

@Serializable
enum class SourceContainmentDocument {
    @SerialName("direct") DIRECT,
    @SerialName("descendants") DESCENDANTS,
}

@Serializable
sealed interface SourceVisibilitySelectionDocument {
    @Serializable @SerialName("any") data object Any : SourceVisibilitySelectionDocument

    @Serializable
    @SerialName("exact")
    data class Exact(
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val values: List<SourceDeclarationVisibilityDocument>
    ) : SourceVisibilitySelectionDocument
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

    @Serializable @SerialName("parameters") data object Parameters : SourceEntityFilterDocument

    @Serializable @SerialName("calls") data object Calls : SourceEntityFilterDocument

    @Serializable @SerialName("references") data object References : SourceEntityFilterDocument
}

@Serializable
sealed interface SourceEntitySelectionDocument {
    @Serializable @SerialName("none") data object None : SourceEntitySelectionDocument

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
        fun parse(raw: Int): Refinement<SourceLineCountDocument, SourceLineCountDocumentFailure> =
            when {
                raw < 0 -> Refinement.Rejected(SourceLineCountDocumentFailure.NEGATIVE)
                raw > MAX_SOURCE_READ_LINE_COUNT -> Refinement.Rejected(SourceLineCountDocumentFailure.TOO_LARGE)
                else -> Refinement.Refined(SourceLineCountDocument(raw))
            }
    }
}

internal object SourceLineCountDocumentSerializer :
    RefiningIntSerializer<SourceLineCountDocument>(
        serialName = "io.github.amichne.kast.protocol.contract.SourceLineCountDocument",
        minimum = 0,
        maximum = MAX_SOURCE_READ_LINE_COUNT.toLong(),
    ) {
    override fun raw(value: SourceLineCountDocument): Int = value.value

    override fun refine(raw: Int): Refinement<SourceLineCountDocument, *> = SourceLineCountDocument.parse(raw)
}

@Serializable
sealed interface SourceTextRequestDocument {
    @Serializable @SerialName("complete") data object Complete : SourceTextRequestDocument

    @Serializable @SerialName("none") data object None : SourceTextRequestDocument

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
        fun parse(raw: Int): Refinement<SourceEntityLimitDocument, SourceEntityLimitDocumentFailure> =
            when {
                raw < 1 -> Refinement.Rejected(SourceEntityLimitDocumentFailure.NOT_POSITIVE)
                raw > MAX_SOURCE_READ_ENTITY_LIMIT -> Refinement.Rejected(SourceEntityLimitDocumentFailure.TOO_LARGE)
                else -> Refinement.Refined(SourceEntityLimitDocument(raw))
            }
    }
}

internal object SourceEntityLimitDocumentSerializer :
    RefiningIntSerializer<SourceEntityLimitDocument>(
        serialName = "io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument",
        minimum = 1,
        maximum = MAX_SOURCE_READ_ENTITY_LIMIT.toLong(),
    ) {
    override fun raw(value: SourceEntityLimitDocument): Int = value.value

    override fun refine(raw: Int): Refinement<SourceEntityLimitDocument, *> = SourceEntityLimitDocument.parse(raw)
}

enum class SourceTextByteLimitDocumentFailure {
    NOT_POSITIVE
}

@JvmInline
@Serializable(with = SourceTextByteLimitDocumentSerializer::class)
value class SourceTextByteLimitDocument private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<SourceTextByteLimitDocument, SourceTextByteLimitDocumentFailure> =
            if (raw < 1L) {
                Refinement.Rejected(SourceTextByteLimitDocumentFailure.NOT_POSITIVE)
            } else {
                Refinement.Refined(SourceTextByteLimitDocument(raw))
            }
    }
}

internal object SourceTextByteLimitDocumentSerializer :
    RefiningLongSerializer<SourceTextByteLimitDocument>(
        serialName = "io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument",
        minimum = 1,
    ) {
    override fun raw(value: SourceTextByteLimitDocument): Long = value.value

    override fun refine(raw: Long): Refinement<SourceTextByteLimitDocument, *> = SourceTextByteLimitDocument.parse(raw)
}

@Serializable
sealed interface SourceReadPageDocument {
    @Serializable @SerialName("first") data object First : SourceReadPageDocument

    @Serializable @SerialName("continue") data class Continue(val continuation: ProtocolText) : SourceReadPageDocument
}

@Serializable
enum class SourceReadFormatDocument {
    @SerialName("expanded") EXPANDED,
    @SerialName("compact") COMPACT,
}

@Serializable(with = SourceReadRequestSerializer::class)
@KeepGeneratedSerializer
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class SourceReadRequest(
    val anchor: SourceReadAnchorDocument,
    val region: SourceRegionSelectionDocument,
    val entities: SourceEntitySelectionDocument,
    val text: SourceTextRequestDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val entityLimit: SourceEntityLimitDocument = sourceReadDefault(SourceEntityLimitDocument.parse(250)),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val textByteLimit: SourceTextByteLimitDocument = sourceReadDefault(SourceTextByteLimitDocument.parse(65_536)),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val page: SourceReadPageDocument = SourceReadPageDocument.First,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val format: SourceReadFormatDocument = SourceReadFormatDocument.EXPANDED,
) : OperationRequest

private fun <T, F> sourceReadDefault(value: Refinement<T, F>): T =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Invalid source read default")
    }

internal object SourceReadRequestSerializer : KSerializer<SourceReadRequest> {
    private val delegate = SourceReadRequest.generatedSerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: SourceReadRequest) {
        delegate.serialize(encoder, value.requireCanonicalSyntax())
    }

    override fun deserialize(decoder: Decoder): SourceReadRequest =
        when (val json = decoder as? kotlinx.serialization.json.JsonDecoder) {
            null -> delegate.deserialize(decoder).requireCanonicalSyntax()
            else ->
                when (val admitted = SourceRequestIngress.decode(json.decodeJsonElement(), json.json)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> throw SourceRequestSerializationException(admitted.failure)
                }
        }
}

private fun SourceReadRequest.requireCanonicalSyntax(): SourceReadRequest =
    if (anchor.hasCanonicalSyntax() && entities.hasCanonicalSyntax()) this
    else throw SerializationException("SourceReadRequest rejected non-canonical request syntax")

private fun SourceReadAnchorDocument.hasCanonicalSyntax(): Boolean {
    val selector =
        when (this) {
            is SourceReadAnchorDocument.Candidate -> selector
            is SourceReadAnchorDocument.Symbol -> selector
            is SourceReadAnchorDocument.Source -> selector
        }
    return when (val admitted = SourceReadAnchorDocument.admit(selector)) {
        is Refinement.Refined -> admitted.value::class == this::class
        is Refinement.Rejected -> false
    }
}

private fun SourceEntitySelectionDocument.hasCanonicalSyntax(): Boolean =
    when (this) {
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
    return keys.size == keys.distinct().size
}

private fun SourceEntityFilterDocument.Declarations.hasCanonicalSyntax(): Boolean =
    kinds.isNotEmpty() && kinds.size == kinds.distinct().size && visibility.hasCanonicalSyntax()

private fun SourceVisibilitySelectionDocument.hasCanonicalSyntax(): Boolean =
    when (this) {
        SourceVisibilitySelectionDocument.Any -> true
        is SourceVisibilitySelectionDocument.Exact -> values.isNotEmpty() && values.size == values.distinct().size
    }

enum class SourceCoordinateUnitDocument {
    UTF16_CODE_UNIT
}

enum class SourceLengthDocumentFailure {
    NEGATIVE
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

sealed interface SourceSnapshotContextDocument {
    data class Published(val generation: EvidenceGeneration, val sourceState: ProtocolText) :
        SourceSnapshotContextDocument

    data class Live(val evidence: LiveReadEvidence) : SourceSnapshotContextDocument
}

data class SourceSnapshotDocument(
    val canonicalRoot: ProtocolText,
    val context: SourceSnapshotContextDocument,
    val file: ProtocolText,
    val textIdentity: ProtocolText,
    val coordinateUnit: SourceCoordinateUnitDocument,
    val length: SourceLengthDocument,
) {
    constructor(
        canonicalRoot: ProtocolText,
        generation: Long,
        sourceState: ProtocolText,
        file: ProtocolText,
        textIdentity: ProtocolText,
        coordinateUnit: SourceCoordinateUnitDocument,
        length: SourceLengthDocument,
    ) : this(
        canonicalRoot,
        SourceSnapshotContextDocument.Published(
            when (val admitted = EvidenceGeneration.parse(generation)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("A source snapshot requires an admitted generation")
            },
            sourceState,
        ),
        file,
        textIdentity,
        coordinateUnit,
        length,
    )
}

enum class SourceSelectionRangeDocumentFailure {
    REVERSED
}

@ConsistentCopyVisibility
data class SourceSelectionRangeDocument
private constructor(
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
    NEGATIVE
}

@JvmInline
value class SourceNestingDepthDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<SourceNestingDepthDocument, SourceNestingDepthDocumentFailure> =
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
        fun parse(raw: String): Refinement<ProtocolSourceText, ProtocolSourceTextFailure> =
            when {
                raw.length > MAX_SOURCE_READ_TEXT_LENGTH -> Refinement.Rejected(ProtocolSourceTextFailure.TOO_LONG)
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

    data class Withheld(val reason: SourceTextWithheldReasonDocument) : SourceTextProjectionDocument
}
