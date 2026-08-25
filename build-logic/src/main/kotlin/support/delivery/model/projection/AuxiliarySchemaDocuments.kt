package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val AUX_SHA256 = "^[0-9a-f]{64}\$"
private const val AUX_SHA1 = "^[0-9a-f]{40}\$"

@Serializable
internal data class ProofReceiptSchemaDocument(
    @SerialName("\$id") val id: String = "https://kast.michne.com/schema/proof-receipt-v1.json",
    @SerialName("\$schema") val schema: String = "https://json-schema.org/draft/2020-12/schema",
    val additionalProperties: Boolean = false,
    val properties: ProofReceiptSchemaProperties = ProofReceiptSchemaProperties(),
    val required: List<String> = proofReceiptRequiredProperties,
    val title: String = "Kast proof receipt",
    val type: String = "object",
)

@Serializable
internal data class ProofReceiptSchemaProperties(
    val artifactDigests: SchemaPatternStringMap = sha256Map(),
    val baseRevision: SchemaPatternString = SchemaPatternString(AUX_SHA1),
    val commandDigest: SchemaPatternString = SchemaPatternString(AUX_SHA256),
    val declaredInputDigest: SchemaPatternString = SchemaPatternString(AUX_SHA256),
    val dependencyReceiptDigests: SchemaPatternStringMap = sha256Map(),
    val exactHead: SchemaPatternString = SchemaPatternString(AUX_SHA1),
    val gateId: SchemaPatternString = SchemaPatternString(
        "^KVP-[0-9]{3}-(RED|GREEN|COMPLETE-GATE)\$",
    ),
    val observedProofValues: SchemaNonEmptyStringMap = SchemaNonEmptyStringMap(),
    val programFingerprint: SchemaPatternString = SchemaPatternString(AUX_SHA256),
    val receiptDigest: SchemaPatternString = SchemaPatternString(AUX_SHA256),
    val receiptId: SchemaPatternString = SchemaPatternString(
        "^KVP-[0-9]{3}-(RED-RECEIPT|GREEN-RECEIPT|COMPLETE)\$",
    ),
    val recordedAtUtc: SchemaFormatString = SchemaFormatString("date-time"),
    val requirementFingerprint: SchemaPatternString = SchemaPatternString(AUX_SHA256),
    val schemaVersion: SchemaIntegerConst = SchemaIntegerConst(1),
    val taskId: SchemaPatternString = SchemaPatternString("^KVP-[0-9]{3}\$"),
)

@Serializable
internal data class RequirementTraceSchemaDocument(
    @SerialName("\$id") val id: String =
        "https://kast.michne.com/schema/requirement-trace-v1.json",
    @SerialName("\$schema") val schema: String =
        "https://json-schema.org/draft/2020-12/schema",
    val additionalProperties: Boolean = false,
    val properties: RequirementTraceSchemaProperties = RequirementTraceSchemaProperties(),
    val required: List<String> = listOf("schemaVersion", "programFingerprint", "entries"),
    val title: String = "Kast requirement trace",
    val type: String = "object",
)

@Serializable
internal data class RequirementTraceSchemaProperties(
    val entries: RequirementTraceEntryArraySchema = RequirementTraceEntryArraySchema(),
    val programFingerprint: SchemaPatternString = SchemaPatternString(AUX_SHA256),
    val schemaVersion: SchemaIntegerConst = SchemaIntegerConst(1),
)

@Serializable
internal data class RequirementTraceEntryArraySchema(
    val items: RequirementTraceEntrySchema = RequirementTraceEntrySchema(),
    val minItems: Int = 1,
    val type: String = "array",
)

@Serializable
internal data class RequirementTraceEntrySchema(
    val additionalProperties: Boolean = false,
    val properties: RequirementTraceEntrySchemaProperties = RequirementTraceEntrySchemaProperties(),
    val required: List<String> = listOf(
        "requirementId", "statement", "implementationTaskIds", "enforcementGateIds",
        "finalRevalidationTaskId", "proofStateSource",
    ),
    val type: String = "object",
)

@Serializable
internal data class RequirementTraceEntrySchemaProperties(
    val enforcementGateIds: SchemaStringArray = SchemaStringArray(),
    val finalRevalidationTaskId: SchemaStringConst = SchemaStringConst("KVP-042"),
    val implementationTaskIds: SchemaStringArray = SchemaStringArray(),
    val proofStateSource: SchemaStringConst = SchemaStringConst("ADMITTED_RECEIPTS"),
    val requirementId: SchemaString = SchemaString(),
    val statement: SchemaString = SchemaString(),
)

private fun sha256Map() = SchemaPatternStringMap(SchemaPatternString(AUX_SHA256))

private val proofReceiptRequiredProperties = listOf(
    "schemaVersion", "receiptId", "baseRevision", "programFingerprint",
    "requirementFingerprint", "exactHead", "taskId", "gateId", "dependencyReceiptDigests",
    "declaredInputDigest", "commandDigest", "observedProofValues", "artifactDigests",
    "recordedAtUtc", "receiptDigest",
)
