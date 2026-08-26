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
private const val COMPATIBILITY_TASK_ID = "KVP-012"

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
    val capabilities: List<String>,
)

internal sealed interface IdeHostCompatibilityMetadataFailure {
    data object MalformedDocument : IdeHostCompatibilityMetadataFailure
    data object UnsupportedSchemaVersion : IdeHostCompatibilityMetadataFailure
    data object WrongTaskIdentity : IdeHostCompatibilityMetadataFailure

    data class CompatibilityRejected(
        val failure: IdeHostCompatibilityFailure,
    ) : IdeHostCompatibilityMetadataFailure
}

internal object IdeHostCompatibilityMetadata {
    /**
     * Proof transition: `String + IdeHostCompatibilityPolicy ->
     * Refinement<AdmittedIdeHostCompatibility, IdeHostCompatibilityMetadataFailure>`.
     *
     * Decodes the closed KVP-012 report schema and establishes that its complete tuple is admitted
     * by the supplied exact policy. Malformed documents, unsupported schema/task identities, and
     * finite compatibility rejections remain [IdeHostCompatibilityMetadataFailure] data. Raw JSON
     * and field text are extracted only here, at the generated build-report boundary.
     */
    internal fun decode(
        raw: String,
        policy: IdeHostCompatibilityPolicy,
    ): Refinement<AdmittedIdeHostCompatibility, IdeHostCompatibilityMetadataFailure> {
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
        val candidate = IdeHostCompatibilityCandidate(
            document.ideBuild,
            document.kotlinPluginBuild,
            document.kastPluginVersion,
            document.runtimeProtocolIdentity,
            document.operationRegistryDigest,
            document.wireSchemaDigest,
            document.capabilities,
        )
        return when (val admission = policy.admit(candidate)) {
            is IdeHostCompatibilityAdmission.Admitted -> Refinement.Refined(admission.compatibility)
            is IdeHostCompatibilityAdmission.Rejected -> Refinement.Rejected(
                IdeHostCompatibilityMetadataFailure.CompatibilityRejected(admission.failure),
            )
        }
    }
}
