package support.delivery

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal enum class ProjectionArtifactId(val repositoryPath: String) {
    PROGRAM("gradle/delivery/kast-vfs-passive-reused-index-program.json"),
    REQUIREMENT_TRACE("gradle/delivery/kast-vfs-passive-requirements.json"),
    DELIVERY_PROGRAM_SCHEMA("gradle/delivery/schema/delivery-program.schema.json"),
    PROOF_RECEIPT_SCHEMA("gradle/delivery/schema/proof-receipt.schema.json"),
    REQUIREMENT_TRACE_SCHEMA("gradle/delivery/schema/requirement-trace.schema.json"),
}

internal data class ProjectionGeneration(
    val program: String,
    val requirementTrace: String,
    val deliveryProgramSchema: String,
    val proofReceiptSchema: String,
    val requirementTraceSchema: String,
) {
    fun content(id: ProjectionArtifactId): String = when (id) {
        ProjectionArtifactId.PROGRAM -> program
        ProjectionArtifactId.REQUIREMENT_TRACE -> requirementTrace
        ProjectionArtifactId.DELIVERY_PROGRAM_SCHEMA -> deliveryProgramSchema
        ProjectionArtifactId.PROOF_RECEIPT_SCHEMA -> proofReceiptSchema
        ProjectionArtifactId.REQUIREMENT_TRACE_SCHEMA -> requirementTraceSchema
    }

    fun orderedContents(): List<String> = ProjectionArtifactId.entries.map(::content)

    fun contentsByPath(): Map<String, String> = ProjectionArtifactId.entries.associate {
        it.repositoryPath to content(it)
    }

    fun replacing(id: ProjectionArtifactId, content: String): ProjectionGeneration =
        ProjectionGeneration(
            program = if (id == ProjectionArtifactId.PROGRAM) content else program,
            requirementTrace = if (id == ProjectionArtifactId.REQUIREMENT_TRACE) {
                content
            } else {
                requirementTrace
            },
            deliveryProgramSchema = if (id == ProjectionArtifactId.DELIVERY_PROGRAM_SCHEMA) {
                content
            } else {
                deliveryProgramSchema
            },
            proofReceiptSchema = if (id == ProjectionArtifactId.PROOF_RECEIPT_SCHEMA) {
                content
            } else {
                proofReceiptSchema
            },
            requirementTraceSchema = if (id == ProjectionArtifactId.REQUIREMENT_TRACE_SCHEMA) {
                content
            } else {
                requirementTraceSchema
            },
        )

}

internal object DeterministicProgramProjection {
    fun generate(program: ValidatedProgram): ProjectionGeneration = ProjectionGeneration(
        program = canonicalJson(program.projection()) + "\n",
        requirementTrace = canonicalJson(program.requirementTraceProjection()) + "\n",
        deliveryProgramSchema = schemaDocument(
            DeliveryProgramSchemaDocument.serializer(),
            DeliveryProgramSchemaDocument(),
        ),
        proofReceiptSchema = schemaDocument(
            ProofReceiptSchemaDocument.serializer(),
            ProofReceiptSchemaDocument(),
        ),
        requirementTraceSchema = schemaDocument(
            RequirementTraceSchemaDocument.serializer(),
            RequirementTraceSchemaDocument(),
        ),
    )
}

internal enum class DeliveryProjectionFailure {
    ARTIFACT_SET_MISMATCH,
    NON_REPEATABLE_GENERATION,
    MALFORMED_JSON,
    NON_CANONICAL_JSON,
    STATUS_FIELD_PRESENT,
    SCHEMA_AUTHORITY_MISMATCH,
    SCHEMA_VALIDATION_FAILED,
}

@ConsistentCopyVisibility
internal data class AdmittedDeterministicProgramProjection internal constructor(
    val generation: ProjectionGeneration,
    val artifactDigests: Map<ProjectionArtifactId, Sha256>,
)

internal sealed interface DeliveryProjectionAdmission {
    data class Admitted(val projection: AdmittedDeterministicProgramProjection) :
        DeliveryProjectionAdmission

    data class Rejected(val failure: DeliveryProjectionFailure) : DeliveryProjectionAdmission
}

internal sealed interface ProjectionGenerationRefinement {
    data class Refined(val generation: ProjectionGeneration) : ProjectionGenerationRefinement
    data class Rejected(val failure: DeliveryProjectionFailure) : ProjectionGenerationRefinement
}

private val deliveryProjectionJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

/**
 * Proof transition: path-keyed artifact bytes -> `ProjectionGeneration`.
 *
 * Establishes that every and only the five closed KVP-005 artifacts is present. Expected failure
 * is [DeliveryProjectionFailure.ARTIFACT_SET_MISMATCH]. Raw path keys are extracted only by the
 * Gradle property boundary.
 */
internal fun refineProjectionGeneration(
    contents: Map<String, String>,
): ProjectionGenerationRefinement {
    val expected = ProjectionArtifactId.entries.mapTo(mutableSetOf()) { it.repositoryPath }
    if (contents.keys != expected) {
        return ProjectionGenerationRefinement.Rejected(
            DeliveryProjectionFailure.ARTIFACT_SET_MISMATCH,
        )
    }
    return ProjectionGenerationRefinement.Refined(
        ProjectionGeneration(
            program = contents.getValue(ProjectionArtifactId.PROGRAM.repositoryPath),
            requirementTrace = contents.getValue(
                ProjectionArtifactId.REQUIREMENT_TRACE.repositoryPath,
            ),
            deliveryProgramSchema = contents.getValue(
                ProjectionArtifactId.DELIVERY_PROGRAM_SCHEMA.repositoryPath,
            ),
            proofReceiptSchema = contents.getValue(
                ProjectionArtifactId.PROOF_RECEIPT_SCHEMA.repositoryPath,
            ),
            requirementTraceSchema = contents.getValue(
                ProjectionArtifactId.REQUIREMENT_TRACE_SCHEMA.repositoryPath,
            ),
        ),
    )
}

/**
 * Proof transition: two `ProjectionGeneration` values ->
 * `AdmittedDeterministicProgramProjection`.
 *
 * Establishes byte-identical generation, canonical JSON, absence of writable status fields, exact
 * generated schema authority, and schema-valid program and requirement projections. Expected
 * failure is the closed [DeliveryProjectionFailure] set. Raw JSON extraction is confined to this
 * schema-application boundary; admitted artifact bytes may be extracted only by the Gradle
 * generation and verification boundary.
 */
internal fun admitDeterministicProgramProjection(
    first: ProjectionGeneration,
    second: ProjectionGeneration,
): DeliveryProjectionAdmission {
    if (first != second) return rejected(DeliveryProjectionFailure.NON_REPEATABLE_GENERATION)
    val parsed = mutableMapOf<ProjectionArtifactId, JsonElement>()
    for (id in ProjectionArtifactId.entries) {
        val content = first.content(id)
        val element = try {
            deliveryProjectionJson.parseToJsonElement(content)
        } catch (_: SerializationException) {
            return rejected(DeliveryProjectionFailure.MALFORMED_JSON)
        }
        if (canonicalJson(element) + "\n" != content) {
            return rejected(DeliveryProjectionFailure.NON_CANONICAL_JSON)
        }
        if (scanForStatusField(element) == StatusFieldScan.Present) {
            return rejected(DeliveryProjectionFailure.STATUS_FIELD_PRESENT)
        }
        parsed[id] = element
    }
    if (admitSchemaAuthority(first) == SchemaAuthorityAdmission.Rejected) {
        return rejected(DeliveryProjectionFailure.SCHEMA_AUTHORITY_MISMATCH)
    }
    val programSchema = parsed.getValue(ProjectionArtifactId.DELIVERY_PROGRAM_SCHEMA) as JsonObject
    val traceSchema = parsed.getValue(ProjectionArtifactId.REQUIREMENT_TRACE_SCHEMA) as JsonObject
    if (validateSchema(parsed.getValue(ProjectionArtifactId.PROGRAM), programSchema, programSchema) !=
        SchemaAdmission.Accepted
    ) {
        return rejected(DeliveryProjectionFailure.SCHEMA_VALIDATION_FAILED)
    }
    if (validateSchema(
            parsed.getValue(ProjectionArtifactId.REQUIREMENT_TRACE),
            traceSchema,
            traceSchema,
        ) != SchemaAdmission.Accepted
    ) {
        return rejected(DeliveryProjectionFailure.SCHEMA_VALIDATION_FAILED)
    }
    return DeliveryProjectionAdmission.Admitted(
        AdmittedDeterministicProgramProjection(
            first,
            ProjectionArtifactId.entries.associateWith { sha256(first.content(it)) },
        ),
    )
}

private sealed interface SchemaAuthorityAdmission {
    data object Admitted : SchemaAuthorityAdmission
    data object Rejected : SchemaAuthorityAdmission
}

private fun admitSchemaAuthority(generation: ProjectionGeneration): SchemaAuthorityAdmission = try {
    val matches = deliveryProjectionJson.decodeFromString(
        DeliveryProgramSchemaDocument.serializer(),
        generation.deliveryProgramSchema,
    ) == DeliveryProgramSchemaDocument() &&
        deliveryProjectionJson.decodeFromString(
            ProofReceiptSchemaDocument.serializer(),
            generation.proofReceiptSchema,
        ) == ProofReceiptSchemaDocument() &&
        deliveryProjectionJson.decodeFromString(
            RequirementTraceSchemaDocument.serializer(),
            generation.requirementTraceSchema,
        ) == RequirementTraceSchemaDocument()
    if (matches) SchemaAuthorityAdmission.Admitted else SchemaAuthorityAdmission.Rejected
} catch (_: SerializationException) {
    SchemaAuthorityAdmission.Rejected
}

private sealed interface SchemaAdmission {
    data object Accepted : SchemaAdmission
    data object Rejected : SchemaAdmission
}

private fun validateSchema(
    instance: JsonElement,
    schema: JsonObject,
    root: JsonObject,
): SchemaAdmission {
    val reference = (schema["\$ref"] as? JsonPrimitive)?.content
    if (reference != null) {
        val name = reference.removePrefix("#/\$defs/")
        val definitions = root["\$defs"] as? JsonObject ?: return SchemaAdmission.Rejected
        val target = definitions[name] as? JsonObject ?: return SchemaAdmission.Rejected
        return validateSchema(instance, target, root)
    }
    schema["const"]?.let { if (it != instance) return SchemaAdmission.Rejected }
    when ((schema["type"] as? JsonPrimitive)?.content) {
        null -> Unit
        "object" -> if (instance !is JsonObject) return SchemaAdmission.Rejected
        "array" -> if (instance !is JsonArray) return SchemaAdmission.Rejected
        "string" -> if (instance !is JsonPrimitive || !instance.isString) {
            return SchemaAdmission.Rejected
        }
        "integer" -> if (instance !is JsonPrimitive || instance.longOrNull == null) {
            return SchemaAdmission.Rejected
        }
        else -> return SchemaAdmission.Rejected
    }
    if (instance is JsonPrimitive && instance.isString) {
        val minimumLength = (schema["minLength"] as? JsonPrimitive)?.intOrNull
        if (minimumLength != null && instance.content.length < minimumLength) {
            return SchemaAdmission.Rejected
        }
        val pattern = (schema["pattern"] as? JsonPrimitive)?.content
        if (pattern != null && !Regex(pattern).containsMatchIn(instance.content)) {
            return SchemaAdmission.Rejected
        }
    }
    if (instance is JsonPrimitive && instance.longOrNull != null) {
        val minimum = (schema["minimum"] as? JsonPrimitive)?.longOrNull
        if (minimum != null && instance.longOrNull!! < minimum) return SchemaAdmission.Rejected
    }
    if (instance is JsonArray) {
        val minItems = (schema["minItems"] as? JsonPrimitive)?.intOrNull
        if (minItems != null && instance.size < minItems) return SchemaAdmission.Rejected
        val unique = (schema["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true
        if (unique && instance.distinct().size != instance.size) return SchemaAdmission.Rejected
        val itemSchema = schema["items"] as? JsonObject
        if (itemSchema != null && instance.any {
                validateSchema(it, itemSchema, root) != SchemaAdmission.Accepted
            }
        ) {
            return SchemaAdmission.Rejected
        }
    }
    if (instance is JsonObject) {
        val minProperties = (schema["minProperties"] as? JsonPrimitive)?.intOrNull
        if (minProperties != null && instance.size < minProperties) return SchemaAdmission.Rejected
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        if (required.any { it !in instance }) return SchemaAdmission.Rejected
        for ((name, value) in instance) {
            val propertySchema = properties[name] as? JsonObject
            if (propertySchema != null) {
                if (validateSchema(value, propertySchema, root) != SchemaAdmission.Accepted) {
                    return SchemaAdmission.Rejected
                }
            } else {
                when (val additional = schema["additionalProperties"]) {
                    is JsonPrimitive -> if (additional.booleanOrNull == false) {
                        return SchemaAdmission.Rejected
                    }
                    is JsonObject -> if (
                        validateSchema(value, additional, root) != SchemaAdmission.Accepted
                    ) {
                        return SchemaAdmission.Rejected
                    }
                    else -> Unit
                }
            }
        }
    }
    return SchemaAdmission.Accepted
}

private sealed interface StatusFieldScan {
    data object Absent : StatusFieldScan
    data object Present : StatusFieldScan
}

private fun scanForStatusField(element: JsonElement): StatusFieldScan = when (element) {
    is JsonObject -> if (
        "status" in element || element.values.any { scanForStatusField(it) == StatusFieldScan.Present }
    ) StatusFieldScan.Present else StatusFieldScan.Absent
    is JsonArray -> if (element.any {
            scanForStatusField(it) == StatusFieldScan.Present
        }
    ) StatusFieldScan.Present else StatusFieldScan.Absent
    is JsonPrimitive -> StatusFieldScan.Absent
}

private fun <T> schemaDocument(
    serializer: kotlinx.serialization.KSerializer<T>,
    document: T,
): String = canonicalJson(deliveryProjectionJson.encodeToJsonElement(serializer, document)) + "\n"

internal fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        canonicalJson(it.key) + ":" + canonicalJson(it.value)
    }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonPrimitive -> if (value.isString) canonicalJson(value.content) else value.content
}

private fun rejected(failure: DeliveryProjectionFailure) =
    DeliveryProjectionAdmission.Rejected(failure)
