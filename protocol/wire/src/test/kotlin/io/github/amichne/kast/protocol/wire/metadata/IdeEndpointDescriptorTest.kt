package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointDescriptorTest {
    @Test
    fun `the exact v2 descriptor retains every refined endpoint authority`() {
        val descriptor = IdeEndpointDescriptorV2.create(
            fixtureEndpointCandidate(),
            fixtureEndpointPolicy(),
        ).admittedDescriptor()

        assertEquals(IdeEndpointSchema.V2, descriptor.schema)
        assertEquals("/workspace/kast", descriptor.canonicalRoot.value)
        assertEquals(IdeEndpointHostKind.IDE_PROJECT, descriptor.hostKind)
        assertEquals(1L, descriptor.processId.value)
        assertEquals(FIXTURE_IDE_BUILD, descriptor.compatibility.ideBuild.value)
        assertEquals(
            FIXTURE_KOTLIN_PLUGIN_BUILD,
            descriptor.compatibility.kotlinPluginBuild.value,
        )
        assertEquals(FIXTURE_KAST_PLUGIN_VERSION, descriptor.compatibility.kastPluginVersion.value)
        assertEquals(
            FIXTURE_RUNTIME_PROTOCOL,
            descriptor.compatibility.runtimeProtocolIdentity.value,
        )
        assertEquals(
            FIXTURE_REGISTRY_DIGEST,
            descriptor.compatibility.operationRegistryDigest.value,
        )
        assertEquals(FIXTURE_WIRE_DIGEST, descriptor.compatibility.wireSchemaDigest.value)
        assertEquals("/tmp/kast-ide.sock", descriptor.socketPath.value)
        assertEquals(IdeEndpointFraming.LENGTH_PREFIXED_JSON_V1, descriptor.framing)
        assertEquals(0L, descriptor.runtimeEpoch.value)
        assertEquals(
            CanonicalHostedCapabilities.capabilities,
            descriptor.capabilities.capabilities,
        )
        assertEquals(
            setOf(HostedCapabilityIntent.ADD_DECLARATION),
            descriptor.capabilities.capabilities
                .single { it.operation == CanonicalOperation.CHANGE_PLAN }
                .intents,
        )
    }

    @Test
    fun `canonical encoding round trips to the same descriptor bytes`() {
        val policy = fixtureEndpointPolicy()
        val encoded = IdeEndpointDescriptorV2.create(
            fixtureEndpointCandidate(),
            policy,
        ).admittedDescriptor().encode()
        val decoded = IdeEndpointDescriptorV2.admit(encoded.document, policy).admittedDescriptor()

        assertEquals(encoded.document, decoded.encode().document)
    }

}
internal const val FIXTURE_IDE_BUILD = "262.9437.185"
internal const val FIXTURE_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"
internal const val FIXTURE_KAST_PLUGIN_VERSION = "1.2.3"
internal const val FIXTURE_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
internal const val FIXTURE_REGISTRY_DIGEST =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
internal const val FIXTURE_WIRE_DIGEST =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"

internal fun fixtureEndpointCandidate() = IdeEndpointDescriptorCandidate(
    schema = "kast.ide.endpoint.v2",
    canonicalRoot = "/workspace/kast",
    hostKind = "IDE_PROJECT",
    processId = 1,
    ideBuild = FIXTURE_IDE_BUILD,
    kotlinPluginBuild = FIXTURE_KOTLIN_PLUGIN_BUILD,
    kastPluginVersion = FIXTURE_KAST_PLUGIN_VERSION,
    runtimeProtocolIdentity = FIXTURE_RUNTIME_PROTOCOL,
    operationRegistryDigest = FIXTURE_REGISTRY_DIGEST,
    wireSchemaDigest = FIXTURE_WIRE_DIGEST,
    socketPath = "/tmp/kast-ide.sock",
    framing = "length-prefixed-json-v1",
    runtimeEpoch = 0,
    capabilities = CanonicalHostedCapabilities.candidates,
)

internal fun fixtureEndpointPolicy(): IdeHostCompatibilityPolicy = when (
    val refinement = IdeHostCompatibilityPolicy.define(
        IdeHostCompatibilityCandidate(
            ideBuild = FIXTURE_IDE_BUILD,
            kotlinPluginBuild = FIXTURE_KOTLIN_PLUGIN_BUILD,
            kastPluginVersion = FIXTURE_KAST_PLUGIN_VERSION,
            runtimeProtocolIdentity = FIXTURE_RUNTIME_PROTOCOL,
            operationRegistryDigest = FIXTURE_REGISTRY_DIGEST,
            wireSchemaDigest = FIXTURE_WIRE_DIGEST,
            capabilities = fixtureEndpointCandidate().capabilities.map(
                HostedCapabilityCandidate::operationId,
            ),
        ),
    )
) {
    is Refinement.Refined -> refinement.value
    is Refinement.Rejected -> fail("fixture endpoint policy rejected: ${refinement.failure}")
}

internal fun IdeEndpointDescriptorAdmission.admittedDescriptor(): IdeEndpointDescriptorV2 =
    when (this) {
        is IdeEndpointDescriptorAdmission.Admitted -> descriptor
        is IdeEndpointDescriptorAdmission.Rejected -> fail("endpoint unexpectedly rejected: $failure")
    }
