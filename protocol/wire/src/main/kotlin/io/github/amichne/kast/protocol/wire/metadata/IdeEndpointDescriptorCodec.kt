package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
private data class IdeEndpointDescriptorDocument(
    val schema: String,
    val canonicalRoot: String,
    val hostKind: String,
    val processId: Long,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val socketPath: String,
    val framing: String,
    val runtimeEpoch: Long,
    val capabilities: List<String>,
)

class EncodedIdeEndpointDescriptor private constructor(
    val document: String,
) {
    companion object {
        /**
         * Proof transition: `IdeEndpointDescriptorV2 -> EncodedIdeEndpointDescriptor`.
         *
         * Preserves the admitted descriptor as the sole configured JSON format's canonical bytes
         * using the compiler-generated serializer. Raw JSON leaves only through [document] at the
         * endpoint publication or transport boundary.
         */
        internal fun from(descriptor: IdeEndpointDescriptorV2): EncodedIdeEndpointDescriptor =
            EncodedIdeEndpointDescriptor(
                wireJson.encodeToString(
                    IdeEndpointDescriptorDocument.serializer(),
                    descriptor.toDocument(),
                ),
            )
    }
}

internal object IdeEndpointDescriptorCodec {
    /**
     * Proof transition: `String + IdeHostCompatibilityPolicy ->
     * IdeEndpointDescriptorAdmission`.
     *
     * Decodes the generated closed document, refines every primitive through
     * [IdeEndpointDescriptorV2.create], and requires canonical byte equality before returning an
     * admitted descriptor. [IdeEndpointDescriptorFailure] is the closed expected failure. Raw JSON
     * and document fields remain inside this codec boundary.
     */
    fun admit(
        raw: String,
        policy: IdeHostCompatibilityPolicy,
    ): IdeEndpointDescriptorAdmission {
        val document = try {
            wireJson.decodeFromString(IdeEndpointDescriptorDocument.serializer(), raw)
        } catch (_: SerializationException) {
            return IdeEndpointDescriptorAdmission.Rejected(
                IdeEndpointDescriptorFailure.MalformedDocument,
            )
        } catch (_: IllegalArgumentException) {
            return IdeEndpointDescriptorAdmission.Rejected(
                IdeEndpointDescriptorFailure.MalformedDocument,
            )
        }
        return when (val admission = IdeEndpointDescriptorV2.create(document.toCandidate(), policy)) {
            is IdeEndpointDescriptorAdmission.Rejected -> admission
            is IdeEndpointDescriptorAdmission.Admitted -> {
                if (admission.descriptor.encode().document == raw) {
                    admission
                } else {
                    IdeEndpointDescriptorAdmission.Rejected(
                        IdeEndpointDescriptorFailure.NonCanonicalDocument,
                    )
                }
            }
        }
    }
}

private fun IdeEndpointDescriptorDocument.toCandidate() = IdeEndpointDescriptorCandidate(
    schema,
    canonicalRoot,
    hostKind,
    processId,
    ideBuild,
    kotlinPluginBuild,
    kastPluginVersion,
    runtimeProtocolIdentity,
    operationRegistryDigest,
    wireSchemaDigest,
    socketPath,
    framing,
    runtimeEpoch,
    capabilities,
)

private fun IdeEndpointDescriptorV2.toDocument() = IdeEndpointDescriptorDocument(
    schema = schema.identity,
    canonicalRoot = canonicalRoot.value,
    hostKind = hostKind.identity,
    processId = processId.value,
    ideBuild = compatibility.ideBuild.value,
    kotlinPluginBuild = compatibility.kotlinPluginBuild.value,
    kastPluginVersion = compatibility.kastPluginVersion.value,
    runtimeProtocolIdentity = compatibility.runtimeProtocolIdentity.value,
    operationRegistryDigest = compatibility.operationRegistryDigest.value,
    wireSchemaDigest = compatibility.wireSchemaDigest.value,
    socketPath = socketPath.value,
    framing = framing.identity,
    runtimeEpoch = runtimeEpoch.value,
    capabilities = compatibility.capabilities.capabilities.map { it.operation.id.value },
)
