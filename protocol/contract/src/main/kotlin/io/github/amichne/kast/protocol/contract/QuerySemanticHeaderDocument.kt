// Generated from query-delivery.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QuerySemanticHeaderDocumentOriginalOutcome {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
    @SerialName("rejected_document") REJECTED_DOCUMENT,
    @SerialName("rejected") REJECTED,
}

@Serializable
enum class QuerySemanticHeaderDocumentCompletionPolicy {
    @SerialName("COMPLETE_ONLY") COMPLETE_ONLY,
    @SerialName("UNAVAILABLE") UNAVAILABLE,
}

@Serializable
enum class QuerySemanticHeaderDocumentCompletionModel {
    @SerialName("COMPILER_RESOLVED_STATIC_V1") COMPILER_RESOLVED_STATIC_V1,
    @SerialName("UNAVAILABLE") UNAVAILABLE,
}

@Serializable
data class QuerySemanticHeaderDocumentCompletion(
    @SerialName("policy") val policy: QuerySemanticHeaderDocumentCompletionPolicy,
    @SerialName("model") val model: QuerySemanticHeaderDocumentCompletionModel,
    @SerialName("interpretation") val interpretation: ProtocolText?,
    @SerialName("cause") val cause: ProtocolText?,
)

@Serializable
@SerialName("RETAINED_RESULT")
data class QuerySemanticHeaderDocumentAuthorityRetainedResult(@SerialName("result") val result: QueryResultReference) :
    QuerySemanticHeaderDocumentAuthority

@Serializable
@SerialName("LIVE")
data class QuerySemanticHeaderDocumentAuthorityLive(
    @SerialName("root") val root: ProtocolText,
    @SerialName("host") val host: ProtocolText,
    @SerialName("epoch") val epoch: Long,
    @SerialName("contentView") val contentView: ProtocolText,
    @SerialName("version") val version: Long,
) : QuerySemanticHeaderDocumentAuthority

@Serializable
@SerialName("UNAVAILABLE")
data object QuerySemanticHeaderDocumentAuthorityUnavailable : QuerySemanticHeaderDocumentAuthority

@Serializable
enum class QuerySemanticHeaderDocumentAuthorityLiveDigestContentView {
    @SerialName("SAVED_PSI_COMMITTED") SAVED_PSI_COMMITTED
}

@Serializable
@SerialName("LIVE_DIGEST")
data class QuerySemanticHeaderDocumentAuthorityLiveDigest(
    @SerialName("workspace_digest") val workspaceDigest: QueryWorkspaceDigest,
    @SerialName("host") val host: ProtocolText,
    @SerialName("epoch") val epoch: Long,
    @SerialName("contentView") val contentView: QuerySemanticHeaderDocumentAuthorityLiveDigestContentView,
    @SerialName("version") val version: Long,
) : QuerySemanticHeaderDocumentAuthority

@Serializable sealed interface QuerySemanticHeaderDocumentAuthority

@Serializable
enum class QuerySemanticHeaderDocumentCoverageType {
    @SerialName("COMPLETE") COMPLETE,
    @SerialName("QUALIFIED") QUALIFIED,
    @SerialName("UNKNOWN") UNKNOWN,
}

@Serializable
data class QuerySemanticHeaderDocumentCoverageProgress(
    @SerialName("type") val type: ProtocolText?,
    @SerialName("reason") val reason: ProtocolText?,
    @SerialName("next_action") val nextAction: ProtocolText?,
)

@Serializable
data class QuerySemanticHeaderDocumentCoverage(
    @SerialName("type") val type: QuerySemanticHeaderDocumentCoverageType,
    @SerialName("exhaustive") val exhaustive: Boolean?,
    @SerialName("known_minimum") val knownMinimum: Long?,
    @SerialName("limitations") val limitations: List<ProtocolText>,
    @SerialName("progress") val progress: QuerySemanticHeaderDocumentCoverageProgress,
)

@Serializable
data class QuerySemanticHeaderDocumentBlockers(
    @SerialName("failure") val failure: ProtocolText?,
    @SerialName("rejection") val rejection: ProtocolText?,
    @SerialName("cause") val cause: ProtocolText?,
    @SerialName("stop") val stop: ProtocolText?,
    @SerialName("qualification") val qualification: ProtocolText?,
    @SerialName("codes") val codes: List<ProtocolText>,
    @SerialName("failure_count") val failureCount: Long?,
    @SerialName("kinds") val kinds: List<ProtocolText>,
)

@Serializable
data class QuerySemanticHeaderDocumentOmissions(
    @SerialName("codes") val codes: List<ProtocolText>,
    @SerialName("count") val count: Long?,
)

@Serializable
enum class QuerySemanticHeaderDocumentPresentationDetail {
    @SerialName("INLINE") INLINE,
    @SerialName("DEFERRED_DETAIL") DEFERRED_DETAIL,
    @SerialName("UNAVAILABLE") UNAVAILABLE,
}

@Serializable
enum class QuerySemanticHeaderDocumentPresentationDetailRead {
    @SerialName("NONE") NONE,
    @SerialName("FRAGMENT") FRAGMENT,
    @SerialName("FINAL_FRAGMENT") FINAL_FRAGMENT,
}

@Serializable
data class QuerySemanticHeaderDocumentPresentation(
    @SerialName("rows") val rows: Long,
    @SerialName("accumulated_rows") val accumulatedRows: Long?,
    @SerialName("evidence_presented") val evidencePresented: Long,
    @SerialName("evidence_total") val evidenceTotal: Long?,
    @SerialName("detail") val detail: QuerySemanticHeaderDocumentPresentationDetail,
    @SerialName("deferred_count") val deferredCount: Long,
    @SerialName("detail_read") val detailRead: QuerySemanticHeaderDocumentPresentationDetailRead,
)

@Serializable
@SerialName("READ_RESULT")
data class QuerySemanticHeaderDocumentRecoveryReadResult(
    @SerialName("result") val result: QueryResultReference,
    @SerialName("cursor") val cursor: Long,
    @SerialName("evidence_cursor") val evidenceCursor: Long,
) : QuerySemanticHeaderDocumentRecovery

@Serializable
@SerialName("RESUME")
data class QuerySemanticHeaderDocumentRecoveryResume(@SerialName("continuation") val continuation: ProtocolText) :
    QuerySemanticHeaderDocumentRecovery

@Serializable
enum class QuerySemanticHeaderDocumentRecoveryUnavailableReason {
    @SerialName("NO_RETAINED_AUTHORITY") NO_RETAINED_AUTHORITY,
    @SerialName("AUTHORITY_REJECTED") AUTHORITY_REJECTED,
}

@Serializable
@SerialName("UNAVAILABLE")
data class QuerySemanticHeaderDocumentRecoveryUnavailable(
    @SerialName("reason") val reason: QuerySemanticHeaderDocumentRecoveryUnavailableReason
) : QuerySemanticHeaderDocumentRecovery

@Serializable sealed interface QuerySemanticHeaderDocumentRecovery

@Serializable
data class QuerySemanticHeaderDocument(
    @SerialName("original_outcome") val originalOutcome: QuerySemanticHeaderDocumentOriginalOutcome,
    @SerialName("completion") val completion: QuerySemanticHeaderDocumentCompletion,
    @SerialName("authority") val authority: QuerySemanticHeaderDocumentAuthority,
    @SerialName("coverage") val coverage: QuerySemanticHeaderDocumentCoverage,
    @SerialName("blockers") val blockers: QuerySemanticHeaderDocumentBlockers,
    @SerialName("omissions") val omissions: QuerySemanticHeaderDocumentOmissions,
    @SerialName("presentation") val presentation: QuerySemanticHeaderDocumentPresentation,
    @SerialName("recovery") val recovery: QuerySemanticHeaderDocumentRecovery,
)
