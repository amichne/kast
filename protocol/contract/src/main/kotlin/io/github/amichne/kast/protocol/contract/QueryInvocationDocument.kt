package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class QueryInvocationStop {
    COMPLETED,
    TERMINAL_INCOMPLETE,
    BUDGET_INCREASE_REQUIRED,
    INVALID_STATE,
    NON_ADVANCING,
    CANCELLED,
    TIME_LIMIT,
    WORK_LIMIT,
    RETAINED_BYTES_LIMIT,
    RETENTION_FAILED,
}

/** The items array is this deterministic preview; its truncation says nothing about semantic coverage. */
@Serializable
sealed interface QueryPreviewDocument {
    val rowCount: Int
    val encodedBytes: Long

    @Serializable
    @SerialName("INLINE")
    data class Inline(
        @SerialName("row_count") @ProtocolIntegerConstraint(minimum = 0) override val rowCount: Int,
        @SerialName("encoded_bytes") @ProtocolIntegerConstraint(minimum = 2) override val encodedBytes: Long,
    ) : QueryPreviewDocument

    @Serializable
    @SerialName("PREFIX")
    data class Prefix(
        @SerialName("row_count") @ProtocolIntegerConstraint(minimum = 0) override val rowCount: Int,
        @SerialName("encoded_bytes") @ProtocolIntegerConstraint(minimum = 2) override val encodedBytes: Long,
    ) : QueryPreviewDocument
}

enum class QueryInvocationDocumentFailure {
    INVALID_COUNT,
    INVALID_PREVIEW_BYTES,
    INVALID_INLINE,
    INVALID_PREFIX,
    MISSING_FAILURE,
    UNEXPECTED_FAILURE,
}

@ConsistentCopyVisibility
@Serializable(with = QueryInvocationDocumentSerializer::class)
data class QueryInvocationDocument
private constructor(
    @SerialName("accumulated_row_count") @ProtocolIntegerConstraint(minimum = 0) val accumulatedRowCount: Int,
    val preview: QueryPreviewDocument,
    val outcome: QueryInvocationOutcome,
) {
    val stop: QueryInvocationStop
        get() = outcome.kind

    val failure: QueryOriginalFailureDocument?
        get() = outcome.failure

    fun withPreview(value: QueryPreviewDocument): Refinement<QueryInvocationDocument, QueryInvocationDocumentFailure> =
        create(accumulatedRowCount, value, stop, failure)

    companion object {
        fun create(
            count: Int,
            preview: QueryPreviewDocument,
            stop: QueryInvocationStop,
            failure: QueryOriginalFailureDocument? = null,
        ): Refinement<QueryInvocationDocument, QueryInvocationDocumentFailure> =
            when {
                count < 0 || preview.rowCount !in 0..count ->
                    Refinement.Rejected(QueryInvocationDocumentFailure.INVALID_COUNT)
                preview.encodedBytes < 2 || preview.rowCount == 0 && preview.encodedBytes != 2L ->
                    Refinement.Rejected(QueryInvocationDocumentFailure.INVALID_PREVIEW_BYTES)
                preview is QueryPreviewDocument.Inline && preview.rowCount != count ->
                    Refinement.Rejected(QueryInvocationDocumentFailure.INVALID_INLINE)
                preview is QueryPreviewDocument.Prefix && preview.rowCount == count ->
                    Refinement.Rejected(QueryInvocationDocumentFailure.INVALID_PREFIX)
                stop == QueryInvocationStop.INVALID_STATE && failure == null ->
                    Refinement.Rejected(QueryInvocationDocumentFailure.MISSING_FAILURE)
                failure != null &&
                    stop !in
                        setOf(
                            QueryInvocationStop.INVALID_STATE,
                            QueryInvocationStop.NON_ADVANCING,
                            QueryInvocationStop.RETENTION_FAILED,
                        ) -> Refinement.Rejected(QueryInvocationDocumentFailure.UNEXPECTED_FAILURE)
                else ->
                    Refinement.Refined(
                        QueryInvocationDocument(count, preview, QueryInvocationOutcome.from(stop, failure))
                    )
            }
    }
}

@Serializable
@SerialName("io.github.amichne.kast.protocol.contract.QueryInvocationDocument")
internal data class QueryInvocationBoundary(
    @SerialName("accumulated_row_count") @ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647) val count: Int,
    val preview: QueryPreviewDocument,
    val stop: QueryInvocationOutcome,
)

internal object QueryInvocationDocumentSerializer : KSerializer<QueryInvocationDocument> {
    private val delegate = QueryInvocationBoundary.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryInvocationDocument) =
        delegate.serialize(
            encoder,
            QueryInvocationBoundary(value.accumulatedRowCount, value.preview, value.outcome),
        )

    override fun deserialize(decoder: Decoder): QueryInvocationDocument {
        val input = delegate.deserialize(decoder)
        return when (
            val admitted =
                QueryInvocationDocument.create(input.count, input.preview, input.stop.kind, input.stop.failure)
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> throw SerializationException("Query invocation rejected: ${admitted.failure}")
        }
    }
}
