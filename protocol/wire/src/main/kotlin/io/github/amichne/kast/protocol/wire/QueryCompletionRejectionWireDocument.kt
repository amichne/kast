@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyProgressDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryOriginalFailureDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Strict rejection rows use the same admission and wire owner as ordinary result rows. */
@Serializable
internal data class QueryCompletionRejectionWireDocument(
    val model: QueryStaticModelDocument,
    val cause: QueryCompletionCauseDocument,
    val originalCoverage: QueryCompletionCoverageDocument,
    val stop: QueryInvocationStop,
    val evidence: QueryCompletionEvidenceWireDocument,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val policyProgress: QueryCompletionPolicyProgressDocument = QueryCompletionPolicyProgressDocument.EvidenceOnly,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val originalFailure: QueryOriginalFailureDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnosticReadId: io.github.amichne.kast.protocol.contract.QueryDiagnosticReadIdentity? = null,
)

@Serializable
internal sealed interface QueryCompletionEvidenceWireDocument {
    @Serializable
    @SerialName("RETAINED")
    data class Retained(
        val result: QueryResultReference,
        @ProtocolCollectionConstraint(maximumItems = MAX_PROTOCOL_ITEMS) val preview: List<QueryResultItemWireDocument>,
        val question: QueryQuestionDocument,
    ) : QueryCompletionEvidenceWireDocument

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCompletionRetentionFailure) : QueryCompletionEvidenceWireDocument
}

internal fun QueryRunRejection.CompletionUnproven.toCompletionWire() =
    QueryCompletionRejectionWireDocument(
        model,
        cause,
        originalCoverage,
        stop,
        when (val retained = evidence) {
            is QueryCompletionEvidenceDocument.Retained ->
                QueryCompletionEvidenceWireDocument.Retained(
                    retained.result,
                    retained.preview.values.map { it.toWire() },
                    retained.question,
                )
            is QueryCompletionEvidenceDocument.Unavailable ->
                QueryCompletionEvidenceWireDocument.Unavailable(retained.cause)
        },
        policyProgress,
        originalFailure,
        diagnosticReadId,
    )

internal fun QueryCompletionRejectionWireDocument.toContract():
    WireDocumentConversion<QueryRunRejection.CompletionUnproven> =
    evidence.toContract().mapConverted {
        QueryRunRejection.CompletionUnproven(
            model,
            cause,
            originalCoverage,
            stop,
            it,
            policyProgress,
            originalFailure,
            diagnosticReadId,
        )
    }

private fun QueryCompletionEvidenceWireDocument.toContract(): WireDocumentConversion<QueryCompletionEvidenceDocument> =
    when (this) {
        is QueryCompletionEvidenceWireDocument.Unavailable ->
            WireDocumentConversion.Converted(QueryCompletionEvidenceDocument.Unavailable(cause))
        is QueryCompletionEvidenceWireDocument.Retained ->
            preview
                .convertEach { it.toContract() }
                .flatMapConverted { rows ->
                    BoundedProtocolList.create(rows).toWireDocumentConversion().mapConverted {
                        QueryCompletionEvidenceDocument.Retained(result, it, question)
                    }
                }
    }
