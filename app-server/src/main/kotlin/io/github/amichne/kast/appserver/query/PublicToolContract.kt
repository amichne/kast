package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.schema.*
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

sealed interface PublicToolInputFailure {
    data object SchemaMismatch : PublicToolInputFailure

    data object SchemaRejected : PublicToolInputFailure

    data object SyntaxRejected : PublicToolInputFailure

    data class SourceAnchor(val cause: SourceReadAnchorDocumentFailure) : PublicToolInputFailure

    data class Parameter(val parameter: PublicToolParameter, val rule: PublicToolRule) : PublicToolInputFailure

    /** A required first parameter makes the constraint's affected parameter set non-empty by construction. */
    data class Constraint(
        val firstParameter: PublicToolParameter,
        val remainingParameters: Set<PublicToolParameter>,
        val rule: PublicToolRule,
    ) : PublicToolInputFailure {
        val parameters: Set<PublicToolParameter> = setOf(firstParameter) + remainingParameters
    }
}

@Serializable
enum class PublicToolParameter(val path: String) {
    SOURCE_DECLARATION_NAME("source.declarationName"),
    SOURCE_WORD("source.word"),
    LOCATION_FILE("source.file"),
    DIRECTORY("scope.relativeDirectoryPath"),
    PACKAGE("scope.packageName"),
    DIAGNOSTIC_PATH("relativePath"),
    TERMINAL_STEP("terminalStep"),
    EXECUTION_MODE("executionMode"),
    EXECUTION_BUDGET_MAX_WORK_UNITS("executionBudget.maxWorkUnits"),
    EXECUTION_BUDGET_MAX_RETURNED_BYTES("executionBudget.maxReturnedBytes"),
    DETAIL_RESULT("detail.result"),
    DETAIL_KIND("detail.kind"),
    DETAIL_CURSOR("detail.ordinal"),
}

@Serializable
enum class PublicToolRule(val correction: String) {
    SIMPLE_NAME("Supply an unqualified declaration name; put its package in scope.packageName."),
    INDEXED_WORD(
        "Supply one ASCII identifier word of at most 256 characters; matching is case-sensitive and whole-word."
    ),
    WORKSPACE_RELATIVE_PATH("Use a canonical workspace-relative path, or '.' for the root."),
    PACKAGE_NAME("Supply a Kotlin package name such as com.example.orders."),
    CANONICAL_STEP_LIMIT("Use at most 1000 steps before EMIT or 999 before the canonical TRACE suffix."),
    RUNNER_CONTROLLED_WORK_LIMIT(
        "RUNNER_CONTROLLED does not accept maxWorkUnits; remove executionBudget.maxWorkUnits or select BOUNDED."
    ),
    OUTPUT_CONTROL_ALLOWANCE(
        "maxReturnedBytes must be at least $PUBLIC_QUERY_OUTPUT_MINIMUM_BYTES bytes. " +
            "Smaller requests use the protocol control allowance."
    ),
    SAME_RETAINED_RESULT("Use the detail selector with its original retained result."),
    DETAIL_TARGET_KIND("Use a row kind for ROW or an evidence kind for EVIDENCE."),
    DETAIL_CURSOR_CONTROL("Use detail.ordinal for detail reads. Omit cursor and evidence_cursor."),
}

/** Closed lowering result. The operation retains execution, effect, and compiler authority. */
sealed interface PublicToolCanonical {
    data class Query(val request: QueryRunRequest) : PublicToolCanonical

    data class Source(val request: SourceReadRequest) : PublicToolCanonical

    data class Diagnostics(val request: DiagnosticCheckRequest) : PublicToolCanonical

    data class Change(val request: ChangeRequest) : PublicToolCanonical
}

/** Private construction retains presentation identity, schema identity, and typed syntax together. */
class AdmittedPublicTool
private constructor(
    val identity: PublicToolIdentity,
    val catalog: PublicToolCatalog,
    val canonical: PublicToolCanonical,
    internal val syntax: PublicToolDocument,
) : OperationRequest {
    val outputDetail: ToolOutputDetail = ToolOutputDetail.fromVerbose(syntax.verbose)

    companion object {
        internal fun admit(
            identity: PublicToolIdentity,
            raw: ValidatedJsonValue,
            catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
        ): Refinement<AdmittedPublicTool, PublicToolInputFailure> {
            if (raw.schemaDigest != PublicToolContract.schema(identity, catalog).digest) {
                return Refinement.Rejected(PublicToolInputFailure.SchemaMismatch)
            }
            val syntax =
                try {
                    decodePublicTool(identity, raw.element, PublicToolContract.json)
                } catch (_: SerializationException) {
                    return Refinement.Rejected(PublicToolInputFailure.SyntaxRejected)
                }
            val outputAllowance = syntax.admitOutputAllowance()
            if (outputAllowance is Refinement.Rejected) return outputAllowance
            return when (val lowered = syntax.lower()) {
                is Refinement.Rejected -> lowered
                is Refinement.Refined -> {
                    try {
                        when (val canonical = lowered.value) {
                            is PublicToolCanonical.Query ->
                                PublicToolContract.json.encodeToJsonElement(
                                    QueryRunRequest.serializer(),
                                    canonical.request,
                                )
                            is PublicToolCanonical.Source ->
                                PublicToolContract.json.encodeToJsonElement(
                                    SourceReadRequest.serializer(),
                                    canonical.request,
                                )
                            is PublicToolCanonical.Diagnostics ->
                                PublicToolContract.json.encodeToJsonElement(
                                    DiagnosticCheckRequest.serializer(),
                                    canonical.request,
                                )
                            is PublicToolCanonical.Change ->
                                PublicToolContract.json.encodeToJsonElement(
                                    ChangeRequest.serializer(),
                                    canonical.request,
                                )
                        }
                    } catch (_: SerializationException) {
                        return Refinement.Rejected(PublicToolInputFailure.SyntaxRejected)
                    }
                    Refinement.Refined(AdmittedPublicTool(identity, catalog, lowered.value, syntax))
                }
            }
        }
    }
}

private fun PublicToolDocument.admitOutputAllowance(): Refinement<Unit, PublicToolInputFailure> {
    val budget =
        when (this) {
            is PublicToolQuerySymbols ->
                when (val action = request) {
                    is PublicToolRunAction -> action.executionBudget
                    is PublicToolResumeAction -> action.executionBudget
                    is PublicToolReadResultAction -> action.executionBudget
                    is PublicToolReadSourceAction -> action.executionBudget
                }
            is PublicToolCheckDiagnostics,
            is PublicToolAddDeclaration,
            is PublicToolReplaceBody -> null
        }
    val bytes = budget?.maxReturnedBytes
    return if (bytes != null && bytes < PUBLIC_QUERY_OUTPUT_MINIMUM_BYTES)
        Refinement.Rejected(
            PublicToolInputFailure.Parameter(
                PublicToolParameter.EXECUTION_BUDGET_MAX_RETURNED_BYTES,
                PublicToolRule.OUTPUT_CONTROL_ALLOWANCE,
            )
        )
    else Refinement.Refined(Unit)
}

object PublicToolContract {
    /** Public flag adapters use the same optional symbol presentation as omitted output. */
    fun defaultQuerySymbolFields(): List<QuerySymbolFieldDocument> = PublicToolDefaults.output.fields.values

    internal val json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = true
    }
    private val commonEncodingJson =
        Json(json) {
            explicitNulls = false
            encodeDefaults = false
        }

    private val parametersByIdentity by lazy {
        PublicToolIdentity.entries.associateWith { identity ->
            requireNotNull(javaClass.getResourceAsStream(identity.toolName + ".parameters.json"))
                .bufferedReader(Charsets.UTF_8)
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
    }
    private val generationByIdentity by lazy {
        PublicToolIdentity.entries.associateWith { identity ->
            requireNotNull(javaClass.getResourceAsStream(identity.toolName + ".openai-parameters.json"))
                .bufferedReader(Charsets.UTF_8)
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
    }

    private val commonParameters by lazy { readQuerySchema("query_symbols.common.parameters.json") }
    private val commonGenerationParameters by lazy { readQuerySchema("query_symbols.common.openai-parameters.json") }
    private val commonSchema by lazy {
        when (val compiled = NetworkntJsonSchemaCompiler.compile(commonParameters)) {
            is Refinement.Refined -> compiled.value
            is Refinement.Rejected -> error("Invalid packaged common query schema")
        }
    }

    private fun readQuerySchema(resource: String): JsonObject =
        requireNotNull(javaClass.getResourceAsStream(resource)).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }

    private val examplesByIdentity by lazy {
        PublicToolIdentity.entries.associateWith { identity ->
            requireNotNull(javaClass.getResourceAsStream(identity.toolName + ".examples.json"))
                .bufferedReader(Charsets.UTF_8)
                .use { json.decodeFromString(PublicToolExamples.serializer(), it.readText()) }
        }
    }

    private val commonExamples by lazy {
        requireNotNull(javaClass.getResourceAsStream("query_symbols.common.examples.json"))
            .bufferedReader(Charsets.UTF_8)
            .use { json.decodeFromString(PublicToolExamples.serializer(), it.readText()) }
    }

    fun examples(
        identity: PublicToolIdentity,
        catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ): PublicToolExamples =
        if (identity == PublicToolIdentity.QUERY_SYMBOLS && catalog == PublicToolCatalog.COMMON) commonExamples
        else examplesByIdentity.getValue(identity)

    fun generationParameters(
        identity: PublicToolIdentity,
        catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ): JsonObject =
        if (identity == PublicToolIdentity.QUERY_SYMBOLS && catalog == PublicToolCatalog.COMMON)
            commonGenerationParameters
        else generationByIdentity.getValue(identity)

    private val schemas by lazy {
        parametersByIdentity.mapValues { (_, parameters) ->
            when (val compiled = NetworkntJsonSchemaCompiler.compile(parameters)) {
                is Refinement.Refined -> compiled.value
                is Refinement.Rejected -> error("Invalid packaged public tool schema")
            }
        }
    }

    fun parameters(
        identity: PublicToolIdentity,
        catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ): JsonObject =
        if (identity == PublicToolIdentity.QUERY_SYMBOLS && catalog == PublicToolCatalog.COMMON) commonParameters
        else parametersByIdentity.getValue(identity)

    internal fun schema(
        identity: PublicToolIdentity,
        catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ): CompiledJsonSchema =
        if (identity == PublicToolIdentity.QUERY_SYMBOLS && catalog == PublicToolCatalog.COMMON) commonSchema
        else schemas.getValue(identity)

    fun admit(
        identity: PublicToolIdentity,
        raw: JsonElement,
        catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ): Refinement<AdmittedPublicTool, PublicToolInputFailure> =
        when (val admitted = schema(identity, catalog).admit(raw)) {
            is Validation.Rejected -> Refinement.Rejected(PublicToolInputFailure.SchemaRejected)
            is Validation.Validated -> admit(identity, admitted.value, catalog)
        }

    internal fun admit(
        identity: PublicToolIdentity,
        raw: ValidatedJsonValue,
        catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ): Refinement<AdmittedPublicTool, PublicToolInputFailure> = AdmittedPublicTool.admit(identity, raw, catalog)

    fun encode(request: AdmittedPublicTool): JsonElement =
        encodePublicTool(request.syntax, if (request.catalog == PublicToolCatalog.COMMON) commonEncodingJson else json)
}

/** Example values are the exact authored argument documents, not a second input grammar. */
@Serializable data class PublicToolExample(val summary: String, val value: JsonElement)

@Serializable
data class PublicToolExamples(
    val examples: Map<String, PublicToolExample>,
    val invalidExamples: Map<String, PublicToolExample>,
)

/** A bound serializer prevents cross-tool reuse even when two facades share an operation. */
/** Serialization transports the same closed admission failure into a JSON codec caller. */
class PublicToolSerializationException(val failure: PublicToolInputFailure) :
    SerializationException(failure.explanation())

fun PublicToolInputFailure.explanation(): String =
    when (this) {
        PublicToolInputFailure.SchemaMismatch -> "Tool contract mismatch; use the schema for the selected tool."
        PublicToolInputFailure.SchemaRejected ->
            "Arguments must match the selected tool's fields and variants. " +
                "For RUN, provide terminalStep with type EMIT or TRACE; put output there and never TRACE in steps. " +
                "Omit optional controls to use their defaults."
        PublicToolInputFailure.SyntaxRejected -> "Arguments violate the selected tool's bounded value grammar."
        is PublicToolInputFailure.SourceAnchor -> "candidateRef: source anchor rejected (${cause.name})."
        is PublicToolInputFailure.Parameter -> "${parameter.path}: ${rule.correction}"
        is PublicToolInputFailure.Constraint -> "${parameters.joinToString { it.path }}: ${rule.correction}"
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
