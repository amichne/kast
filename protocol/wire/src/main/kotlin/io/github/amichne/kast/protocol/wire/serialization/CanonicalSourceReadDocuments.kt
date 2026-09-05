package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SourceReadRequestWireDocument(
    val anchor: SourceReadAnchorWireDocument,
    val region: SourceRegionSelectionWireDocument,
    val entities: SourceEntitySelectionWireDocument,
    val text: SourceTextRequestWireDocument,
    val entityLimit: Int,
    val textByteLimit: Long,
    val page: SourceReadPageWireDocument,
)

@Serializable
internal sealed interface SourceReadAnchorWireDocument {
    @Serializable
    @SerialName("candidate")
    data class Candidate(val selector: String) : SourceReadAnchorWireDocument

    @Serializable
    @SerialName("symbol")
    data class Symbol(val selector: String) : SourceReadAnchorWireDocument

    @Serializable
    @SerialName("source")
    data class Source(val selector: String) : SourceReadAnchorWireDocument
}

@Serializable
internal sealed interface SourceRegionSelectionWireDocument {
    @Serializable
    @SerialName("anchor")
    data object Anchor : SourceRegionSelectionWireDocument

    @Serializable
    @SerialName("body")
    data class Body(val kind: SourceBodyKindWireDocument) : SourceRegionSelectionWireDocument

    @Serializable
    @SerialName("file")
    data object File : SourceRegionSelectionWireDocument

    @Serializable
    @SerialName("enclosing")
    data class Enclosing(
        val kind: SourceEnclosingRegionKindWireDocument,
    ) : SourceRegionSelectionWireDocument
}

@Serializable
internal enum class SourceBodyKindWireDocument {
    @SerialName("callable") CALLABLE,
    @SerialName("class") CLASS,
}

@Serializable
internal enum class SourceEnclosingRegionKindWireDocument {
    @SerialName("declaration") DECLARATION,
    @SerialName("callable-body") CALLABLE_BODY,
    @SerialName("class-body") CLASS_BODY,
}

@Serializable
internal sealed interface SourceEntitySelectionWireDocument {
    @Serializable
    @SerialName("none")
    data object None : SourceEntitySelectionWireDocument

    @Serializable
    @SerialName("matching")
    data class Matching(
        val containment: SourceContainmentWireDocument,
        val filters: List<SourceEntityFilterWireDocument>,
    ) : SourceEntitySelectionWireDocument
}

@Serializable
internal enum class SourceContainmentWireDocument {
    @SerialName("direct") DIRECT,
    @SerialName("descendants") DESCENDANTS,
}

@Serializable
internal sealed interface SourceEntityFilterWireDocument {
    @Serializable
    @SerialName("declaration")
    data class Declarations(
        val kinds: List<SourceDeclarationKindWireDocument>,
        val visibility: SourceVisibilitySelectionWireDocument,
    ) : SourceEntityFilterWireDocument

    @Serializable
    @SerialName("parameters")
    data object Parameters : SourceEntityFilterWireDocument

    @Serializable
    @SerialName("calls")
    data object Calls : SourceEntityFilterWireDocument

    @Serializable
    @SerialName("references")
    data object References : SourceEntityFilterWireDocument
}

@Serializable
internal sealed interface SourceVisibilitySelectionWireDocument {
    @Serializable
    @SerialName("any")
    data object Any : SourceVisibilitySelectionWireDocument

    @Serializable
    @SerialName("exact")
    data class Exact(
        val values: List<SourceDeclarationVisibilityWireDocument>,
    ) : SourceVisibilitySelectionWireDocument
}

@Serializable
internal enum class SourceDeclarationKindWireDocument {
    @SerialName("classlike") CLASSLIKE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}

@Serializable
internal enum class SourceDeclarationVisibilityWireDocument {
    @SerialName("public") PUBLIC,
    @SerialName("protected") PROTECTED,
    @SerialName("internal") INTERNAL,
    @SerialName("private") PRIVATE,
    @SerialName("local") LOCAL,
}

@Serializable
internal sealed interface SourceTextRequestWireDocument {
    @Serializable
    @SerialName("complete")
    data object Complete : SourceTextRequestWireDocument

    @Serializable
    @SerialName("none")
    data object None : SourceTextRequestWireDocument

    @Serializable
    @SerialName("window")
    data class Window(
        val beforeLines: Int,
        val afterLines: Int,
    ) : SourceTextRequestWireDocument
}

@Serializable
internal sealed interface SourceReadPageWireDocument {
    @Serializable
    @SerialName("first")
    data object First : SourceReadPageWireDocument

    @Serializable
    @SerialName("continue")
    data class Continue(val continuation: String) : SourceReadPageWireDocument
}

@Serializable
internal data class SourceReadResultWireDocument(
    val snapshot: SourceSnapshotWireDocument,
    val region: SourceRegionWireDocument,
    val entities: List<SourceEntityWireDocument>,
    val text: SourceTextProjectionWireDocument,
)

@Serializable
internal data class SourceSnapshotWireDocument(
    val canonicalRoot: String,
    val generation: Long,
    val sourceState: String,
    val file: String,
    val textIdentity: String,
    val coordinateUnit: SourceCoordinateUnitWireDocument,
    val length: Int,
)

@Serializable
internal enum class SourceCoordinateUnitWireDocument {
    @SerialName("utf16-code-unit") UTF16_CODE_UNIT,
}

@Serializable
internal data class SourceSelectionRangeWireDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

@Serializable
internal data class SourceSelectionWireDocument(
    val selector: String,
    val range: SourceSelectionRangeWireDocument,
)

@Serializable
internal data class SourceRegionWireDocument(
    val kind: SourceRegionKindWireDocument,
    val selection: SourceSelectionWireDocument,
)

@Serializable
internal enum class SourceRegionKindWireDocument {
    @SerialName("anchor") ANCHOR,
    @SerialName("declaration") DECLARATION,
    @SerialName("callable-body") CALLABLE_BODY,
    @SerialName("class-body") CLASS_BODY,
    @SerialName("file") FILE,
    @SerialName("window") WINDOW,
}

@Serializable
internal sealed interface SourceDeclarationSemanticIdentityWireDocument {
    @Serializable
    @SerialName("candidate")
    data class Candidate(val selector: String) : SourceDeclarationSemanticIdentityWireDocument
}

@Serializable
internal sealed interface SourceEntityTargetWireDocument {
    @Serializable
    @SerialName("candidate")
    data class Candidate(val selector: String) : SourceEntityTargetWireDocument

    @Serializable
    @SerialName("local")
    data class Local(val selector: String) : SourceEntityTargetWireDocument

    @Serializable
    @SerialName("unresolved")
    data class Unresolved(val reason: SourceUnresolvedReasonWireDocument) : SourceEntityTargetWireDocument
}

@Serializable
internal enum class SourceUnresolvedReasonWireDocument {
    @SerialName("name-not-found") NAME_NOT_FOUND,
    @SerialName("ambiguous") AMBIGUOUS,
    @SerialName("error-type") ERROR_TYPE,
    @SerialName("unsupported-target") UNSUPPORTED_TARGET,
}

@Serializable
internal sealed interface SourceEntityWireDocument {
    @Serializable
    @SerialName("declaration")
    data class Declaration(
        val kind: SourceDeclarationKindWireDocument,
        val name: String,
        val visibility: SourceDeclarationVisibilityWireDocument,
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionWireDocument,
        val semanticIdentity: SourceDeclarationSemanticIdentityWireDocument,
    ) : SourceEntityWireDocument

    @Serializable
    @SerialName("value-parameter")
    data class ValueParameter(
        val name: String,
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionWireDocument,
    ) : SourceEntityWireDocument

    @Serializable
    @SerialName("call")
    data class Call(
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionWireDocument,
        val callee: SourceSelectionWireDocument,
        val target: SourceEntityTargetWireDocument,
    ) : SourceEntityWireDocument

    @Serializable
    @SerialName("reference")
    data class Reference(
        val name: String,
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionWireDocument,
        val target: SourceEntityTargetWireDocument,
    ) : SourceEntityWireDocument
}

@Serializable
internal sealed interface SourceTextProjectionWireDocument {
    @Serializable
    @SerialName("not-requested")
    data object NotRequested : SourceTextProjectionWireDocument

    @Serializable
    @SerialName("returned")
    data class Returned(
        val selection: SourceSelectionWireDocument,
        val text: String,
        val lines: SourceLineRangeWireDocument,
    ) : SourceTextProjectionWireDocument

    @Serializable
    @SerialName("withheld")
    data class Withheld(
        val reason: SourceTextWithheldReasonWireDocument,
    ) : SourceTextProjectionWireDocument
}

@Serializable
internal enum class SourceTextWithheldReasonWireDocument {
    @SerialName("byte-limit-reached") BYTE_LIMIT_REACHED,
    @SerialName("provider-unavailable") PROVIDER_UNAVAILABLE,
}

@Serializable
internal data class SourceReadQualificationWireDocument(
    val knownMinimumEntityCount: Int,
    val limitations: List<SourceReadLimitationWireDocument>,
    val continuation: SourceReadContinuationStateWireDocument,
)

@Serializable
internal enum class SourceReadLimitationWireDocument {
    @SerialName("entity-limit-reached") ENTITY_LIMIT_REACHED,
    @SerialName("text-byte-limit-reached") TEXT_BYTE_LIMIT_REACHED,
    @SerialName("work-limit-reached") WORK_LIMIT_REACHED,
    @SerialName("time-limit-reached") TIME_LIMIT_REACHED,
    @SerialName("dumb-mode-transition") DUMB_MODE_TRANSITION,
    @SerialName("semantic-resolution-incomplete") SEMANTIC_RESOLUTION_INCOMPLETE,
    @SerialName("unsupported-entity") UNSUPPORTED_ENTITY,
    @SerialName("provider-failure") PROVIDER_FAILURE,
}

@Serializable
internal sealed interface SourceReadContinuationStateWireDocument {
    @Serializable
    @SerialName("unavailable")
    data object Unavailable : SourceReadContinuationStateWireDocument

    @Serializable
    @SerialName("available")
    data class Available(val continuation: String) : SourceReadContinuationStateWireDocument
}

@Serializable
internal enum class SourceReadRejectionWireDocument {
    @SerialName("workspace-not-ready") WORKSPACE_NOT_READY,
    @SerialName("workspace-root-mismatch") WORKSPACE_ROOT_MISMATCH,
    @SerialName("stale-generation") STALE_GENERATION,
    @SerialName("source-state-mismatch") SOURCE_STATE_MISMATCH,
    @SerialName("candidate-stale") CANDIDATE_STALE,
    @SerialName("source-selector-stale") SOURCE_SELECTOR_STALE,
    @SerialName("source-snapshot-mismatch") SOURCE_SNAPSHOT_MISMATCH,
    @SerialName("source-unavailable") SOURCE_UNAVAILABLE,
    @SerialName("document-dirty") DOCUMENT_DIRTY,
    @SerialName("psi-document-uncommitted") PSI_DOCUMENT_UNCOMMITTED,
    @SerialName("outside-source-scope") OUTSIDE_SOURCE_SCOPE,
    @SerialName("anchor-not-found") ANCHOR_NOT_FOUND,
    @SerialName("ambiguous-anchor") AMBIGUOUS_ANCHOR,
    @SerialName("region-not-applicable") REGION_NOT_APPLICABLE,
    @SerialName("region-absent") REGION_ABSENT,
    @SerialName("compiler-analysis-unavailable") COMPILER_ANALYSIS_UNAVAILABLE,
    @SerialName("contract-violation") CONTRACT_VIOLATION,
}

@Serializable
internal data class SourceLineRangeWireDocument(val startInclusive: Long, val endInclusive: Long)
