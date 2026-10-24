package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.PublicToolParameter
import io.github.amichne.kast.appserver.query.PublicToolRule
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** RPC rejection preserves request constraints and local authority; the semantic owner decides source freshness. */
@Serializable
internal sealed interface QuerySessionRejectionDetail {
    @Serializable
    @SerialName("REQUEST_CONSTRAINT_REJECTED")
    data class RequestConstraint(
        val parameters: Set<@Serializable(with = PublicToolParameterPathSerializer::class) PublicToolParameter>,
        val rule: PublicToolRule,
    ) : QuerySessionRejectionDetail {
        @Required
        val remedies: Set<PublicToolConstraintRemedy> =
            setOf(PublicToolConstraintRemedy.REMOVE_MAX_WORK_UNITS, PublicToolConstraintRemedy.SELECT_BOUNDED)
    }

    @Serializable
    @SerialName("NEW_RUN_REQUIRES_NEW_SESSION")
    data class NewRun
    private constructor(
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    ) : QuerySessionRejectionDetail {
        companion object {
            val value = NewRun()
        }
    }

    @Serializable
    @SerialName("SOURCE_READ_REQUIRES_NEW_SESSION")
    data class SourceRead
    private constructor(
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    ) : QuerySessionRejectionDetail {
        companion object {
            val value = SourceRead()
        }
    }

    @Serializable
    @SerialName("RESULT_NOT_ISSUED_BY_PRECEDING_REPLY")
    data class ResultNotIssued(
        @SerialName("expected_result") val expected: QueryResultReference?,
        @SerialName("requested_result") val requested: QueryResultReference,
    ) : QuerySessionRejectionDetail {
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    }

    @Serializable
    @SerialName("CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY")
    data class ContinuationNotIssued(
        @SerialName("expected_continuation") val expected: QueryExecutionContinuation.Output?,
        @SerialName("requested_continuation") val requested: QueryExecutionContinuation,
    ) : QuerySessionRejectionDetail {
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    }

    @Serializable
    @SerialName("SOURCE_ANCHOR_REJECTED")
    data class SourceAnchor(val cause: SourceReadAnchorDocumentFailure) : QuerySessionRejectionDetail {
        @Required
        @SerialName("next_action")
        val nextAction: SourceAnchorRecoveryAction = SourceAnchorRecoveryAction.USE_ISSUED_CANDIDATE_SELECTOR
    }
}

@Serializable
internal enum class QuerySessionRecoveryAction {
    START_NEW_QUERY_SESSION
}

@Serializable
internal enum class SourceAnchorRecoveryAction {
    USE_ISSUED_CANDIDATE_SELECTOR
}

@Serializable
internal enum class PublicToolConstraintRemedy {
    REMOVE_MAX_WORK_UNITS,
    SELECT_BOUNDED,
}

/** RPC paths retain the public parameter identity without changing its other serialized projections. */
internal object PublicToolParameterPathSerializer : KSerializer<PublicToolParameter> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PublicToolParameterPath", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PublicToolParameter) = encoder.encodeString(value.path)

    override fun deserialize(decoder: Decoder): PublicToolParameter {
        val path = decoder.decodeString()
        return PublicToolParameter.entries.singleOrNull { it.path == path }
            ?: throw SerializationException("Unknown public tool parameter path")
    }
}
