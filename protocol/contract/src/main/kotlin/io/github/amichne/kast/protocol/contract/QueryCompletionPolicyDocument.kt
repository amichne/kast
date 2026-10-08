@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

/** Compiler-resolved source relationships; this model makes no runtime reachability claim. */
@Serializable
enum class QueryStaticModelDocument {
    COMPILER_RESOLVED_STATIC_V1
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCompletionPolicyDocument {
    companion object {
        val Default: CompleteOnly = CompleteOnly(QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1)
    }

    @Serializable @SerialName("PROGRESSIVE") data object Progressive : QueryCompletionPolicyDocument

    @Serializable
    @SerialName("COMPLETE_ONLY")
    data class CompleteOnly(val model: QueryStaticModelDocument) : QueryCompletionPolicyDocument
}

@Serializable
enum class QueryCompletionUnsupportedReason {
    UNSUPPORTED_OUTPUT,
    AUTOMATIC_EXECUTION_REQUIRED,
}

@Serializable
enum class QueryCompletionUnprovenReason {
    INCOMPLETE_EXECUTION,
    ITEM_FAILURE,
    OMITTED_EVIDENCE,
    CALLBACK_GRAPH_UNPROVEN,
    INVESTIGATION_UNPROVEN,
}

@Serializable
enum class QueryCompletionRetentionFailure {
    UNAVAILABLE,
    CAPACITY_EXCEEDED,
}

/** Original coverage is independent of the policy rejection and cannot be relabeled as complete. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCompletionCoverageDocument {
    @Serializable @SerialName("COMPLETE") data object Complete : QueryCompletionCoverageDocument

    @Serializable
    @SerialName("QUALIFIED")
    data class Qualified(
        val knownMinimum: ProtocolOffset,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val limitations: QueryCompletionLimitationsDocument,
        val progress: QueryQualifiedProgressDocument,
    ) : QueryCompletionCoverageDocument
}

sealed interface QueryCompletionEvidenceDocument {
    data class Retained(
        val result: QueryResultReference,
        val preview: BoundedProtocolList<QueryResultItemDocument>,
        val question: QueryQuestionDocument,
    ) : QueryCompletionEvidenceDocument {
        /** Reading these rows preserves the original policy rejection and performs no semantic replay. */
        fun readRequest(): QueryRunRequest.ReadResult =
            when (val selected = question.output) {
                is QueryOutputDocument.Symbols -> QueryRunRequest.ReadResult.symbols(result, output = selected)
                QueryOutputDocument.Occurrences -> QueryRunRequest.ReadResult.occurrences(result)
                QueryOutputDocument.TraversalRecords -> QueryRunRequest.ReadResult.traversalRecords(result)
                QueryOutputDocument.BindingRows -> QueryRunRequest.ReadResult.bindingRows(result)
                QueryOutputDocument.ValuePaths -> QueryRunRequest.ReadResult.valuePaths(result)
                is QueryOutputDocument.ImpactWitness ->
                    QueryRunRequest.ReadResult.impactWitness(result, selected.section)
            }
    }

    data class Unavailable(val cause: QueryCompletionRetentionFailure) : QueryCompletionEvidenceDocument
}

enum class QueryCompletionLimitationsFailure {
    EMPTY,
    NON_CANONICAL,
}

@Serializable(with = QueryCompletionLimitationsSerializer::class)
class QueryCompletionLimitationsDocument private constructor(val values: List<QueryLimitationDocument>) {
    companion object {
        fun from(
            raw: List<QueryLimitationDocument>
        ): Refinement<
            QueryCompletionLimitationsDocument,
            QueryCompletionLimitationsFailure,
        > =
            when {
                raw.isEmpty() -> Refinement.Rejected(QueryCompletionLimitationsFailure.EMPTY)
                raw != raw.distinct().sortedBy { it.ordinal } ->
                    Refinement.Rejected(QueryCompletionLimitationsFailure.NON_CANONICAL)
                else ->
                    Refinement.Refined(QueryCompletionLimitationsDocument(Collections.unmodifiableList(raw.toList())))
            }
    }

    override fun equals(other: Any?): Boolean = other is QueryCompletionLimitationsDocument && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

internal object QueryCompletionLimitationsSerializer : KSerializer<QueryCompletionLimitationsDocument> {
    private val delegate = ListSerializer(QueryLimitationDocument.serializer())
    override val descriptor =
        annotatedDescriptor(
            delegate.descriptor,
            ProtocolCollectionConstraint(
                minimumItems = 1,
                maximumItems = QueryLimitationDocument.entries.size,
                uniqueItems = true,
            ),
        )

    override fun serialize(encoder: Encoder, value: QueryCompletionLimitationsDocument) =
        delegate.serialize(encoder, value.values)

    override fun deserialize(decoder: Decoder): QueryCompletionLimitationsDocument =
        when (val parsed = QueryCompletionLimitationsDocument.from(delegate.deserialize(decoder))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException("Completion limitations rejected ${parsed.failure}")
        }
}

/** Historical producer progress is retained independently; the rejected policy issues no resume transition. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCompletionPolicyProgressDocument {
    @Serializable @SerialName("EVIDENCE_ONLY") data object EvidenceOnly : QueryCompletionPolicyProgressDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryResultInterpretationDocument {
    @Serializable @SerialName("QUERY_RESULT") data object QueryResult : QueryResultInterpretationDocument

    @Serializable
    @SerialName("POLICY_REJECTED_EVIDENCE")
    data class EvidenceOnly(
        val model: QueryStaticModelDocument,
        val cause: QueryCompletionCauseDocument,
        val originalCoverage: QueryCompletionCoverageDocument,
    ) : QueryResultInterpretationDocument {
        val reason
            get() = cause.reason

        val callbackGraphFailure
            get() = (cause as? QueryCompletionCauseDocument.CallbackGraphUnproven)?.graphFailure
    }
}

@Serializable
enum class QueryCallbackObservationOrigin {
    RELATION,
    WALK,
}

/** Exact position in retained result observation groups and their callback or callable list. */
@Serializable
data class QueryCallbackGraphFailureDocument(
    val cause: QueryCallbackGraphCauseDocument,
    val origin: QueryCallbackObservationOrigin,
    val group: ProtocolOffset,
    val observation: ProtocolOffset,
)
