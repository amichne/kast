package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val SHA256_PATTERN = "^[0-9a-f]{64}\$"
private const val SHA1_PATTERN = "^[0-9a-f]{40}\$"

@Serializable
internal data class DeliveryProgramSchemaDocument(
    @SerialName("\$defs") val definitions: DeliveryProgramSchemaDefinitions =
        DeliveryProgramSchemaDefinitions(),
    @SerialName("\$id") val id: String =
        "https://kast.michne.com/schema/delivery-program-v1.json",
    @SerialName("\$schema") val schema: String =
        "https://json-schema.org/draft/2020-12/schema",
    val additionalProperties: Boolean = false,
    val properties: DeliveryProgramSchemaProperties = DeliveryProgramSchemaProperties(),
    val required: List<String> = deliveryProgramRequiredProperties,
    val title: String = "Kast delivery program",
    val type: String = "object",
)

@Serializable
internal data class DeliveryProgramSchemaDefinitions(
    val task: DeliveryTaskSchema = DeliveryTaskSchema(),
)

@Serializable
internal data class DeliveryProgramSchemaProperties(
    val authorities: SchemaObjectArray = SchemaObjectArray(),
    val authority: SchemaObject = SchemaObject(),
    val effects: SchemaObjectArray = SchemaObjectArray(),
    val gateGraph: SchemaObjectArray = SchemaObjectArray(),
    val installedAcceptance: SchemaObject = SchemaObject(),
    val modules: SchemaNonEmptyObjectArray = SchemaNonEmptyObjectArray(),
    val name: SchemaNonEmptyString = SchemaNonEmptyString(),
    val processGraph: SchemaObject = SchemaObject(),
    val programFingerprint: SchemaPatternString = SchemaPatternString(SHA256_PATTERN),
    val programId: SchemaPatternString = SchemaPatternString("^[a-z][a-z0-9-]*\$"),
    val requirementFingerprint: SchemaPatternString = SchemaPatternString(SHA256_PATTERN),
    val requirements: RequirementArraySchema = RequirementArraySchema(),
    val schemaVersion: SchemaIntegerConst = SchemaIntegerConst(1),
    val sourceDigests: SchemaPatternStringMap = SchemaPatternStringMap(
        SchemaPatternString(SHA256_PATTERN),
    ),
    val specialEdges: SchemaObjectArray = SchemaObjectArray(),
    val targetHead: SchemaPatternString = SchemaPatternString(SHA1_PATTERN),
    val taskOrder: SchemaStringArray = SchemaStringArray(),
    val tasks: SchemaNonEmptyReferenceArray = SchemaNonEmptyReferenceArray(
        SchemaReference("#/\$defs/task"),
    ),
    val terminal: DeliveryTerminalSchema = DeliveryTerminalSchema(),
    val waveCount: SchemaMinimumInteger = SchemaMinimumInteger(1),
)

@Serializable
internal data class RequirementArraySchema(
    val items: RequirementSchema = RequirementSchema(),
    val minItems: Int = 1,
    val type: String = "array",
)

@Serializable
internal data class RequirementSchema(
    val additionalProperties: Boolean = false,
    val properties: RequirementSchemaProperties = RequirementSchemaProperties(),
    val required: List<String> = listOf("id", "statement"),
    val type: String = "object",
)

@Serializable
internal data class RequirementSchemaProperties(
    val id: SchemaString = SchemaString(),
    val statement: SchemaString = SchemaString(),
)

@Serializable
internal data class DeliveryTaskSchema(
    val additionalProperties: Boolean = false,
    val properties: DeliveryTaskSchemaProperties = DeliveryTaskSchemaProperties(),
    val required: List<String> = deliveryTaskRequiredProperties,
    val type: String = "object",
)

@Serializable
internal data class DeliveryTaskSchemaProperties(
    val allowedReads: SchemaNonEmptyStringArray = SchemaNonEmptyStringArray(),
    val allowedWrites: SchemaNonEmptyStringArray = SchemaNonEmptyStringArray(),
    val authorities: SchemaStringArray = SchemaStringArray(),
    val computedWave: SchemaMinimumInteger = SchemaMinimumInteger(0),
    val costClassification: SchemaStringArray = SchemaStringArray(),
    val dependencyExpression: DeliveryDependencySchema = DeliveryDependencySchema(),
    val effectClassification: SchemaStringArray = SchemaStringArray(),
    val forbiddenWork: SchemaNonEmptyStringArray = SchemaNonEmptyStringArray(),
    val goal: SchemaNonEmptyString = SchemaNonEmptyString(),
    val id: SchemaPatternString = SchemaPatternString("^KVP-[0-9]{3}\$"),
    val inputs: SchemaNonEmptyObjectArray = SchemaNonEmptyObjectArray(),
    val internalImplementation: SchemaString = SchemaString(),
    val milestone: SchemaString = SchemaString(),
    val outputs: SchemaNonEmptyObjectArray = SchemaNonEmptyObjectArray(),
    val proof: SchemaObject = SchemaObject(),
    val provesRequirements: SchemaStringArray = SchemaStringArray(),
    val publicInterface: SchemaString = SchemaString(),
    val reviewBoundary: SchemaString = SchemaString(),
    val title: SchemaNonEmptyString = SchemaNonEmptyString(),
    val taskDefinitionDigest: SchemaPatternString = SchemaPatternString(SHA256_PATTERN),
)

@Serializable
internal data class DeliveryDependencySchema(
    val additionalProperties: Boolean = false,
    val properties: DeliveryDependencySchemaProperties = DeliveryDependencySchemaProperties(),
    val required: List<String> = listOf("kind", "taskIds"),
    val type: String = "object",
)

@Serializable
internal data class DeliveryDependencySchemaProperties(
    val kind: SchemaStringConst = SchemaStringConst("allOf"),
    val taskIds: SchemaUniqueStringArray = SchemaUniqueStringArray(),
)

@Serializable
internal data class DeliveryTerminalSchema(
    val additionalProperties: Boolean = false,
    val properties: DeliveryTerminalSchemaProperties = DeliveryTerminalSchemaProperties(),
    val required: List<String> = listOf("taskId", "type", "receiptPath", "derivedOnly"),
    val type: String = "object",
)

@Serializable
internal data class DeliveryTerminalSchemaProperties(
    val derivedOnly: SchemaBooleanConst = SchemaBooleanConst(true),
    val receiptPath: SchemaString = SchemaString(),
    val taskId: SchemaString = SchemaString(),
    val type: SchemaStringConst = SchemaStringConst("BestCaseVfsPassiveReusedIndex"),
)

private val deliveryProgramRequiredProperties = listOf(
    "programFingerprint",
    "schemaVersion",
    "programId",
    "targetHead",
    "requirementFingerprint",
    "requirements",
    "modules",
    "tasks",
    "gateGraph",
    "terminal",
)

private val deliveryTaskRequiredProperties = listOf(
    "id", "title", "goal", "dependencyExpression", "allowedReads", "allowedWrites", "inputs",
    "outputs", "publicInterface", "internalImplementation", "effectClassification",
    "costClassification", "forbiddenWork", "proof", "reviewBoundary",
    "provesRequirements", "taskDefinitionDigest", "computedWave",
)
