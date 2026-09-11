package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.schema.*
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

sealed interface PublicToolInputFailure {
    data object SchemaMismatch : PublicToolInputFailure
    data object SchemaRejected : PublicToolInputFailure
    data object SyntaxRejected : PublicToolInputFailure
    data class Parameter(val parameter: PublicToolParameter, val rule: PublicToolRule) : PublicToolInputFailure
}

enum class PublicToolParameter(val path: String) {
    CLASS_NAME("class_name"), FUNCTION_NAME("function_name"), SOURCE_DECLARATION_NAME("source.declaration_name"),
    DECLARATION_NAME("declaration_name"), DIRECTORY("scope.relative_directory_path"),
    PACKAGE("scope.package_name"), DIAGNOSTIC_PATH("relative_path"),
}
enum class PublicToolRule(val correction: String) {
    SIMPLE_NAME("Supply an unqualified declaration name; put its package in scope.package_name."),
    WORKSPACE_RELATIVE_PATH("Use a canonical workspace-relative path, or '.' for the root."),
    PACKAGE_NAME("Supply a Kotlin package name such as com.example.orders."),
}

/** Closed lowering result. The operation retains execution, effect, and compiler authority. */
sealed interface PublicToolCanonical {
    data class Query(val request: QueryRunRequest) : PublicToolCanonical
    data class Diagnostics(val request: DiagnosticCheckRequest) : PublicToolCanonical
}

/** Private construction retains presentation identity, schema identity, and typed syntax together. */
class AdmittedPublicTool private constructor(
    val identity: PublicToolIdentity,
    val canonical: PublicToolCanonical,
    internal val syntax: PublicToolDocument,
) : OperationRequest {
    companion object {
        internal fun admit(identity: PublicToolIdentity, raw: ValidatedJsonValue): Refinement<AdmittedPublicTool, PublicToolInputFailure> {
            if (raw.schemaDigest != PublicToolContract.schema(identity).digest) {
                return Refinement.Rejected(PublicToolInputFailure.SchemaMismatch)
            }
            val syntax = try {
                decodePublicTool(identity, raw.element, PublicQueryContract.json)
            } catch (_: SerializationException) {
                return Refinement.Rejected(PublicToolInputFailure.SyntaxRejected)
            }
            return when (val lowered = syntax.lower()) {
                is Refinement.Rejected -> lowered
                is Refinement.Refined -> {
                    try {
                        when (val canonical = lowered.value) {
                            is PublicToolCanonical.Query -> PublicQueryContract.json.encodeToJsonElement(QueryRunRequest.serializer(), canonical.request)
                            is PublicToolCanonical.Diagnostics -> PublicQueryContract.json.encodeToJsonElement(DiagnosticCheckRequest.serializer(), canonical.request)
                        }
                    } catch (_: SerializationException) {
                        return Refinement.Rejected(PublicToolInputFailure.SyntaxRejected)
                    }
                    Refinement.Refined(AdmittedPublicTool(identity, lowered.value, syntax))
                }
            }
        }
    }
}

object PublicToolContract {
    private val parametersByIdentity by lazy {
        PublicToolIdentity.entries.associateWith { identity ->
            requireNotNull(javaClass.getResourceAsStream(identity.toolName + ".parameters.json"))
                .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
    }
    private val generationByIdentity by lazy {
        PublicToolIdentity.entries.associateWith { identity ->
            requireNotNull(javaClass.getResourceAsStream(identity.toolName + ".openai-parameters.json"))
                .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
    }
    fun generationParameters(identity: PublicToolIdentity): JsonObject = generationByIdentity.getValue(identity)
    private val schemas by lazy {
        parametersByIdentity.mapValues { (_, parameters) ->
            when (val compiled = NetworkntJsonSchemaCompiler.compile(parameters)) {
                is Refinement.Refined -> compiled.value
                is Refinement.Rejected -> error("Invalid packaged public tool schema")
            }
        }
    }
    fun parameters(identity: PublicToolIdentity): JsonObject = parametersByIdentity.getValue(identity)
    internal fun schema(identity: PublicToolIdentity): CompiledJsonSchema = schemas.getValue(identity)
    fun admit(identity: PublicToolIdentity, raw: JsonElement): Refinement<AdmittedPublicTool, PublicToolInputFailure> =
        when (val admitted = schema(identity).admit(raw)) {
            is Validation.Rejected -> Refinement.Rejected(PublicToolInputFailure.SchemaRejected)
            is Validation.Validated -> admit(identity, admitted.value)
        }
    internal fun admit(identity: PublicToolIdentity, raw: ValidatedJsonValue): Refinement<AdmittedPublicTool, PublicToolInputFailure> =
        AdmittedPublicTool.admit(identity, raw)
    fun encode(request: AdmittedPublicTool): JsonElement = encodePublicTool(request.syntax, PublicQueryContract.json)
}

/** A bound serializer prevents cross-tool reuse even when two facades share an operation. */
/** Serialization transports the same closed admission failure into a JSON codec caller. */
class PublicToolSerializationException(val failure: PublicToolInputFailure) : SerializationException(failure.explanation())

fun PublicToolInputFailure.explanation(): String = when (this) {
    PublicToolInputFailure.SchemaMismatch -> "Tool contract mismatch; use the schema for the selected tool."
    PublicToolInputFailure.SchemaRejected -> "Arguments must contain exactly the selected tool's required fields. Supply null for default controls."
    PublicToolInputFailure.SyntaxRejected -> "Arguments violate the selected tool's bounded value grammar."
    is PublicToolInputFailure.Parameter -> "${parameter.path}: ${rule.correction}"
}

class PublicToolRequestSerializer(private val identity: PublicToolIdentity) : KSerializer<AdmittedPublicTool> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PublicTool.${identity.toolName}")
    override fun deserialize(decoder: Decoder): AdmittedPublicTool {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Tool requests require JSON")
        return when (val admitted = PublicToolContract.admit(identity, input.decodeJsonElement())) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> throw PublicToolSerializationException(admitted.failure)
        }
    }
    override fun serialize(encoder: Encoder, value: AdmittedPublicTool) {
        if (value.identity != identity) throw SerializationException("Tool identity mismatch")
        val output = encoder as? JsonEncoder ?: throw SerializationException("Tool requests require JSON")
        output.encodeJsonElement(PublicToolContract.encode(value))
    }
}
