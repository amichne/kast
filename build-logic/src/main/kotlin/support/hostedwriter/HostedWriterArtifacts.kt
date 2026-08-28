package support.hostedwriter

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
@JvmInline
value class ProgramFingerprint(val value: String)

@Serializable
@JvmInline
value class RepositoryHead(val value: String)

@Serializable
@JvmInline
value class ReceiptDigest(val value: String)

@Serializable
@JvmInline
value class InputDigest(val value: String)

@Serializable
@JvmInline
value class CommandDigest(val value: String)

@Serializable
@JvmInline
value class ArtifactDigest(val value: String)

@Serializable
sealed interface ProofOutcome {
    @Serializable
    data class Complete(val receipt: ProofReceipt) : ProofOutcome

    @Serializable
    data class Qualified(val reason: ProofQualification) : ProofOutcome

    @Serializable
    data class Rejected(val reason: ProofRejection) : ProofOutcome
}

@Serializable
@JvmInline
value class ProofQualification(val value: String)

@Serializable
@JvmInline
value class ProofRejection(val value: String)

@Serializable
data class ProofReceipt(
    val schemaVersion: Int = 1,
    val gateId: ProofGateId,
    val programFingerprint: ProgramFingerprint,
    val repositoryHead: RepositoryHead,
    val dependencyReceiptDigests: Set<ReceiptDigest>,
    val inputDigest: InputDigest,
    val commandDigest: CommandDigest,
    val observedProofs: Set<ObservedProof>,
    val artifactDigests: Set<ArtifactDigest>,
)

@Serializable
enum class InstalledAcceptanceOutcome {
    COMPLETE,
    REUSED,
    QUALIFIED,
    REJECTED,
}

@Serializable
data class InstalledAcceptanceObservation(
    val name: String,
    val outcome: InstalledAcceptanceOutcome,
    val artifactDigest: ArtifactDigest,
)

@Serializable
data class InstalledAcceptanceDocument(
    val schemaVersion: Int = 1,
    val repositoryHead: RepositoryHead,
    val positiveJourney: List<InstalledAcceptanceObservation>,
    val negativeJourneys: List<InstalledAcceptanceObservation>,
)

sealed interface HostedWriterSchemaValidation {
    data object Valid : HostedWriterSchemaValidation

    data class Rejected(
        val messages: Set<String>,
    ) : HostedWriterSchemaValidation
}

/** Checked-in JSON Schema validator used only by the three hosted-writer artifact families. */
object HostedWriterSchemaValidator {
    private val registry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
    )

    fun validate(
        schema: JsonElement,
        document: JsonElement,
    ): HostedWriterSchemaValidation = try {
        val compiled = registry.getSchema(schema.toString())
        val messages = compiled.validate(document.toString(), InputFormat.JSON)
            .mapTo(linkedSetOf()) { it.message }
        if (messages.isEmpty()) {
            HostedWriterSchemaValidation.Valid
        } else {
            HostedWriterSchemaValidation.Rejected(messages)
        }
    } catch (failure: RuntimeException) {
        HostedWriterSchemaValidation.Rejected(
            setOf(failure.message ?: failure::class.java.name),
        )
    }

}
