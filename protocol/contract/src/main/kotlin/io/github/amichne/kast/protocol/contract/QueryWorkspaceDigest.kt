package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class QueryWorkspaceDigestFailure {
    INVALID_SHA256
}

/** A digest binds presentation metadata to an already admitted exact workspace. It grants no capability. */
@JvmInline
@Serializable(with = QueryWorkspaceDigestSerializer::class)
value class QueryWorkspaceDigest private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<QueryWorkspaceDigest, QueryWorkspaceDigestFailure> =
            if (value.matches(Regex("^[0-9a-f]{64}$"))) Refinement.Refined(QueryWorkspaceDigest(value))
            else Refinement.Rejected(QueryWorkspaceDigestFailure.INVALID_SHA256)

        fun fromRoot(exactRoot: String): QueryWorkspaceDigest =
            QueryWorkspaceDigest(
                MessageDigest.getInstance("SHA-256").digest(exactRoot.toByteArray(Charsets.UTF_8)).joinToString("") {
                    "%02x".format(it)
                }
            )
    }
}

internal object QueryWorkspaceDigestSerializer : KSerializer<QueryWorkspaceDigest> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("QueryWorkspaceDigest", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: QueryWorkspaceDigest) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): QueryWorkspaceDigest =
        when (val parsed = QueryWorkspaceDigest.parse(decoder.decodeString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException(parsed.failure.name)
        }
}
