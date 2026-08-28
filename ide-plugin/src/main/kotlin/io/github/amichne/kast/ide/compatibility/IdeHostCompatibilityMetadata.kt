package io.github.amichne.kast.ide.compatibility

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val COMPATIBILITY_SCHEMA_VERSION = 1
private const val COMPATIBILITY_TASK_ID = "IDE-HOST-COMPATIBILITY"

private val compatibilityMetadataJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

@Serializable
internal data class IdeHostCompatibilityReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<HostedCapabilityReportDocument>,
)

@Serializable
internal data class HostedCapabilityReportDocument(
    val operationId: String,
    val intents: List<String>,
)

internal sealed interface IdeHostCompatibilityMetadataFailure {
    data object MalformedDocument : IdeHostCompatibilityMetadataFailure
    data object UnsupportedSchemaVersion : IdeHostCompatibilityMetadataFailure
    data object WrongTaskIdentity : IdeHostCompatibilityMetadataFailure

    data class CompatibilityRejected(
        val failure: IdeHostCompatibilityFailure,
    ) : IdeHostCompatibilityMetadataFailure
}

/** Non-forgeable generated compatibility candidate and its exact admitting policy. */
internal class AdmittedIdeHostCompatibilityMetadata private constructor(
    val candidate: IdeHostCompatibilityCandidate,
    val compatibilityPolicy: IdeHostCompatibilityPolicy,
    val hostedCapabilities: List<HostedCapabilityReportDocument>,
) {
    companion object {
        /**
         * Proof transition: `GeneratedIdeHostCompatibilityMetadata ->
         * Refinement<AdmittedIdeHostCompatibilityMetadata, IdeHostCompatibilityMetadataFailure>`.
         *
         * Establishes the closed IDE-HOST-COMPATIBILITY report identity and retains the exact policy derived from
         * its candidate. Expected metadata or compatibility rejection remains finite
         * [IdeHostCompatibilityMetadataFailure]. Raw JSON leaves only at this generated boundary.
         */
        internal fun admitGenerated(): Refinement<
            AdmittedIdeHostCompatibilityMetadata,
            IdeHostCompatibilityMetadataFailure,
        > {
            val document = when (val parsed = IdeHostCompatibilityMetadata.parseGenerated()) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val candidate = document.candidate
            return when (val definition = IdeHostCompatibilityPolicy.define(candidate)) {
                is Refinement.Refined -> Refinement.Refined(
                    AdmittedIdeHostCompatibilityMetadata(
                        candidate,
                        definition.value,
                        document.hostedCapabilities,
                    ),
                )
                is Refinement.Rejected -> Refinement.Rejected(
                    IdeHostCompatibilityMetadataFailure.CompatibilityRejected(definition.failure),
                )
            }
        }
    }
}

internal object IdeHostCompatibilityMetadata {
    internal data class Parsed(
        val candidate: IdeHostCompatibilityCandidate,
        val hostedCapabilities: List<HostedCapabilityReportDocument>,
    )

    /**
     * Proof transition: `GeneratedIdeHostCompatibilityMetadata ->
     * Refinement<IdeHostCompatibilityCandidate, IdeHostCompatibilityMetadataFailure>`.
     *
     * Establishes the closed report schema and exact task identity for the sole compiled IDE-HOST-COMPATIBILITY
     * document. Expected metadata rejection remains [IdeHostCompatibilityMetadataFailure]. Raw
     * JSON leaves only at this generated compile-time metadata boundary.
     */
    internal fun parseGenerated(): Refinement<
        Parsed,
        IdeHostCompatibilityMetadataFailure,
    > = parse(GeneratedIdeHostCompatibilityMetadata.document)

    /**
     * Proof transition: `String + IdeHostCompatibilityPolicy ->
     * Refinement<AdmittedIdeHostCompatibility, IdeHostCompatibilityMetadataFailure>`.
     *
     * Decodes the closed IDE-HOST-COMPATIBILITY report schema and establishes that its complete tuple is admitted
     * by the supplied exact policy. Malformed documents, unsupported schema/task identities, and
     * finite compatibility rejections remain [IdeHostCompatibilityMetadataFailure] data. Raw JSON
     * and field text are extracted only here, at the generated build-report boundary.
     */
    internal fun decode(
        raw: String,
        policy: IdeHostCompatibilityPolicy,
    ): Refinement<AdmittedIdeHostCompatibility, IdeHostCompatibilityMetadataFailure> {
        val parsedDocument = when (val parsed = parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
        val candidate = parsedDocument.candidate
        return when (val admission = policy.admit(candidate)) {
            is IdeHostCompatibilityAdmission.Admitted -> Refinement.Refined(admission.compatibility)
            is IdeHostCompatibilityAdmission.Rejected -> Refinement.Rejected(
                IdeHostCompatibilityMetadataFailure.CompatibilityRejected(admission.failure),
            )
        }
    }

    /**
     * Proof transition: `String ->
     * Refinement<IdeHostCompatibilityCandidate, IdeHostCompatibilityMetadataFailure>`.
     *
     * Establishes the closed IDE-HOST-COMPATIBILITY document schema and exact schema/task identities before
     * projecting its candidate. Malformed, unsupported, or wrong-task documents remain finite
     * [IdeHostCompatibilityMetadataFailure]. Raw JSON and document fields leave only at this
     * generated compatibility boundary.
     */
    private fun parse(
        raw: String,
    ): Refinement<Parsed, IdeHostCompatibilityMetadataFailure> {
        val document = try {
            compatibilityMetadataJson.decodeFromString(
                IdeHostCompatibilityReportDocument.serializer(),
                raw,
            )
        } catch (_: SerializationException) {
            return Refinement.Rejected(IdeHostCompatibilityMetadataFailure.MalformedDocument)
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(IdeHostCompatibilityMetadataFailure.MalformedDocument)
        }
        if (document.schemaVersion != COMPATIBILITY_SCHEMA_VERSION) {
            return Refinement.Rejected(
                IdeHostCompatibilityMetadataFailure.UnsupportedSchemaVersion,
            )
        }
        if (document.taskId != COMPATIBILITY_TASK_ID) {
            return Refinement.Rejected(IdeHostCompatibilityMetadataFailure.WrongTaskIdentity)
        }
        return Refinement.Refined(
            Parsed(
                candidate = IdeHostCompatibilityCandidate(
                    document.ideBuild,
                    document.kotlinPluginBuild,
                    document.kastPluginVersion,
                    document.runtimeProtocolIdentity,
                    document.operationRegistryDigest,
                    document.wireSchemaDigest,
                    document.capabilities.map(HostedCapabilityReportDocument::operationId),
                ),
                hostedCapabilities = document.capabilities,
            ),
        )
    }
}
