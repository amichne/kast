package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.appserver.schema.ValidatedJsonValue
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class PublicQueryInputFailure {
    SCHEMA_MISMATCH,
    SCHEMA_REJECTED,
    CANONICAL_SYNTAX_REJECTED,
}

/** Public syntax authority, independent of the hosting provider.
 * Validation establishes syntax, not workspace/generation/compiler authority.
 */
object PublicQueryContract {
    val parameters: JsonObject by lazy { resource("query.parameters.json") }
    internal val schema: CompiledJsonSchema by lazy {
        when (val compiled = NetworkntJsonSchemaCompiler.compile(parameters)) {
            is Refinement.Refined -> compiled.value
            is Refinement.Rejected -> error("Invalid packaged public query schema")
        }
    }

    internal val json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun admit(raw: JsonElement): Refinement<AdmittedPublicQuery, PublicQueryInputFailure> =
        when (val admission = schema.admit(raw)) {
            is Validation.Validated -> admit(admission.value)
            is Validation.Rejected -> Refinement.Rejected(PublicQueryInputFailure.SCHEMA_REJECTED)
        }

    internal fun admit(raw: ValidatedJsonValue): Refinement<AdmittedPublicQuery, PublicQueryInputFailure> =
        AdmittedPublicQuery.admit(raw)

    fun encode(request: AdmittedPublicQuery): JsonElement =
        json.encodeToJsonElement(PublicQueryDocument.serializer(), request.syntax)

    private fun resource(name: String): JsonObject =
        requireNotNull(javaClass.getResourceAsStream(name)) {
            "Missing packaged public query schema: $name"
        }.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
}

/** Syntax proof, not a compiler selector or a workspace capability. No unchecked constructor or copy. */
@kotlinx.serialization.Serializable(with = PublicQueryRequestSerializer::class)
class AdmittedPublicQuery private constructor(
    val canonicalRequest: QueryRunRequest,
    internal val syntax: PublicQueryDocument,
) : io.github.amichne.kast.protocol.contract.OperationRequest {
    companion object {
        internal fun admit(raw: ValidatedJsonValue): Refinement<AdmittedPublicQuery, PublicQueryInputFailure> {
            if (raw.schemaDigest != PublicQueryContract.schema.digest) {
                return Refinement.Rejected(PublicQueryInputFailure.SCHEMA_MISMATCH)
            }
            return try {
                val syntax = PublicQueryContract.json.decodeFromJsonElement(
                    PublicQueryDocument.serializer(), raw.element,
                )
                val request = syntax.toCanonicalQuery()
                // Retain the canonical syntax proof (including the engine's UTF-16 name bound).
                // Workspace, generation and compiler reference admission still belong to execution.
                PublicQueryContract.json.encodeToJsonElement(QueryRunRequest.serializer(), request)
                Refinement.Refined(AdmittedPublicQuery(request, syntax))
            } catch (_: SerializationException) {
                Refinement.Rejected(PublicQueryInputFailure.CANONICAL_SYNTAX_REJECTED)
            }
        }
    }
}

/** The same ingress for the CLI and provider; the internal wire codec is unchanged. */
object PublicQueryRequestSerializer : KSerializer<AdmittedPublicQuery> {
    override val descriptor: SerialDescriptor = PublicQueryDocument.serializer().descriptor

    override fun deserialize(decoder: Decoder): AdmittedPublicQuery {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("Public query requests require JSON")
        return when (val admission = PublicQueryContract.admit(input.decodeJsonElement())) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> throw SerializationException("Public query rejected: ${admission.failure}")
        }
    }

    override fun serialize(encoder: Encoder, value: AdmittedPublicQuery) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("Public query requests require JSON")
        output.encodeJsonElement(PublicQueryContract.encode(value))
    }
}

