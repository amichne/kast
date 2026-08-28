package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorAdmission
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorCandidate
import io.github.amichne.kast.protocol.wire.metadata.CanonicalHostedCapabilities
import io.github.amichne.kast.protocol.wire.metadata.HostedCapabilityCandidate
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IdeEndpointAdmissionTest {
    @Test
    @DisplayName("Only one compatible exact-root endpoint yields dispatch capability.")
    fun `only one compatible exact-root endpoint yields dispatch capability`() {
        val fixture = ideEndpointFixture()
        var descriptorReads = 0
        val admission = IdeEndpointAdmitter(
            fixture.socketDirectory,
            fixture.policy,
            IdeEndpointDescriptorReader { location ->
                descriptorReads += 1
                assertEquals(fixture.location.descriptorPath, location.descriptorPath)
                IdeEndpointDescriptorRead.Complete(fixture.document)
            },
            IdeEndpointProcessProbe { processId ->
                assertEquals(FIXTURE_PROCESS_ID, processId.value)
                IdeEndpointProcessObservation.Alive
            },
            IdeEndpointReachabilityProbe { socketPath ->
                assertEquals(fixture.location.socketPath, socketPath)
                IdeEndpointReachability.Reachable
            },
        ).admit(fixture.root)

        val endpoint = (admission as IdeEndpointAdmission.Complete).endpoint
        assertEquals(1, descriptorReads)
        assertSame(fixture.root, endpoint.root)
        assertEquals(fixture.document, endpoint.descriptor.encode().document)
        assertEquals(fixture.location.socketPath.value, endpoint.socketPath.toString())
    }
}

internal data class IdeEndpointFixture(
    val root: CanonicalRoot,
    val socketDirectory: IdeEndpointSocketDirectory,
    val location: IdeEndpointLocation,
    val policy: IdeHostCompatibilityPolicy,
    val document: String,
)

internal fun ideEndpointFixture(
    rootPath: String = FIXTURE_ROOT,
    descriptorRoot: String = rootPath,
    socketPath: String? = null,
    candidateTransform: (IdeEndpointDescriptorCandidate) -> IdeEndpointDescriptorCandidate = { it },
): IdeEndpointFixture {
    val root = CanonicalRoot(Path.of(rootPath))
    val directory = IdeEndpointSocketDirectory.parse("/tmp").refined()
    val endpointRoot = io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
        .parse(rootPath).refined()
    val location = IdeEndpointLocation.locate(directory, endpointRoot).refined()
    val candidate = candidateTransform(
        IdeEndpointDescriptorCandidate(
            schema = "kast.ide.endpoint.v2",
            canonicalRoot = descriptorRoot,
            hostKind = "IDE_PROJECT",
            processId = FIXTURE_PROCESS_ID,
            ideBuild = FIXTURE_IDE_BUILD,
            kotlinPluginBuild = FIXTURE_KOTLIN_BUILD,
            kastPluginVersion = FIXTURE_PLUGIN_VERSION,
            runtimeProtocolIdentity = FIXTURE_RUNTIME_PROTOCOL,
            operationRegistryDigest = FIXTURE_REGISTRY_DIGEST,
            wireSchemaDigest = FIXTURE_WIRE_DIGEST,
            socketPath = socketPath ?: location.socketPath.value,
            framing = "length-prefixed-json-v1",
            runtimeEpoch = 7,
            capabilities = FIXTURE_CAPABILITIES,
        ),
    )
    val policy = IdeHostCompatibilityPolicy.define(candidate.compatibilityCandidate()).refined()
    val document = when (val admitted = IdeEndpointDescriptorV2.create(candidate, policy)) {
        is IdeEndpointDescriptorAdmission.Admitted -> admitted.descriptor.encode().document
        is IdeEndpointDescriptorAdmission.Rejected -> error("fixture descriptor: ${admitted.failure}")
    }
    return IdeEndpointFixture(root, directory, location, policy, document)
}

internal fun IdeEndpointDescriptorCandidate.compatibilityCandidate() =
    IdeHostCompatibilityCandidate(
        ideBuild,
        kotlinPluginBuild,
        kastPluginVersion,
        runtimeProtocolIdentity,
        operationRegistryDigest,
        wireSchemaDigest,
        capabilities.map(HostedCapabilityCandidate::operationId),
    )

internal fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("fixture refinement: $failure")
}

internal const val FIXTURE_ROOT = "/workspace/kast"
internal const val FIXTURE_PROCESS_ID = 42L
internal const val FIXTURE_IDE_BUILD = "262.9437.185"
internal const val FIXTURE_KOTLIN_BUILD = "262.9437.185-IJ"
internal const val FIXTURE_PLUGIN_VERSION = "1.2.3"
internal const val FIXTURE_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
internal const val FIXTURE_REGISTRY_DIGEST =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
internal const val FIXTURE_WIRE_DIGEST =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"
internal val FIXTURE_CAPABILITIES = CanonicalHostedCapabilities.candidates
