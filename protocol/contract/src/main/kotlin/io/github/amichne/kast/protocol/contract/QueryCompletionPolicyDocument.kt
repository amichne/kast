@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Compiler-resolved source relationships; this model makes no runtime reachability claim. */
@Serializable
enum class QueryStaticModelDocument {
    COMPILER_RESOLVED_STATIC_V1
}

@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
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
}

@Serializable
enum class QueryCompletionRetentionFailure {
    UNAVAILABLE,
    CAPACITY_EXCEEDED,
}

/** Original coverage is independent of the policy rejection and cannot be relabeled as complete. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
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

@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface QueryCompletionEvidenceDocument {
    @Serializable
    @SerialName("RETAINED")
    data class Retained(val result: QueryResultReference) : QueryCompletionEvidenceDocument

    @Serializable
    @SerialName("UNAVAILABLE")
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
        ): io.github.amichne.kast.kernel.Refinement<
            QueryCompletionLimitationsDocument,
            QueryCompletionLimitationsFailure,
        > =
            when {
                raw.isEmpty() ->
                    io.github.amichne.kast.kernel.Refinement.Rejected(QueryCompletionLimitationsFailure.EMPTY)
                raw != raw.distinct().sortedBy { it.ordinal } ->
                    io.github.amichne.kast.kernel.Refinement.Rejected(QueryCompletionLimitationsFailure.NON_CANONICAL)
                else ->
                    io.github.amichne.kast.kernel.Refinement.Refined(
                        QueryCompletionLimitationsDocument(java.util.Collections.unmodifiableList(raw.toList()))
                    )
            }
    }

    override fun equals(other: Any?): Boolean = other is QueryCompletionLimitationsDocument && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

internal object QueryCompletionLimitationsSerializer :
    kotlinx.serialization.KSerializer<QueryCompletionLimitationsDocument> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(QueryLimitationDocument.serializer())
    override val descriptor =
        annotatedDescriptor(
            delegate.descriptor,
            ProtocolCollectionConstraint(
                minimumItems = 1,
                maximumItems = QueryLimitationDocument.entries.size,
                uniqueItems = true,
            ),
        )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: QueryCompletionLimitationsDocument) =
        delegate.serialize(encoder, value.values)

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): QueryCompletionLimitationsDocument =
        when (val parsed = QueryCompletionLimitationsDocument.from(delegate.deserialize(decoder))) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> parsed.value
            is io.github.amichne.kast.kernel.Refinement.Rejected ->
                throw kotlinx.serialization.SerializationException("Completion limitations rejected ${parsed.failure}")
        }
}

/** Historical producer progress is retained independently; the rejected policy issues no resume transition. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface QueryCompletionPolicyProgressDocument {
    @Serializable @SerialName("EVIDENCE_ONLY") data object EvidenceOnly : QueryCompletionPolicyProgressDocument
}

@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
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
