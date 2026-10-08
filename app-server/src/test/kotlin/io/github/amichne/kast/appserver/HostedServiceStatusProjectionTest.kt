package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeDescriptor
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.HostedEndpointOwnerPid
import io.github.amichne.kast.appserver.ide.HostedServiceObservation
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusFailure
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusField
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostProvenance
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedServiceStatusProjectionTest {
    private val baseline =
        IdeHostCompatibilityCandidate(
            ideBuild = "262.1.1",
            kotlinPluginBuild = "262.1.1-IJ",
            kastPluginVersion = "0.50.0",
            runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
            operationRegistryDigest = "sha256:${"a".repeat(64)}",
            wireSchemaDigest = "sha256:${"b".repeat(64)}",
            capabilities = emptyList(),
        )
    private val policy = (IdeHostCompatibilityPolicy.define(baseline) as Refinement.Refined).value
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val descriptor =
        ExistingIdeDescriptor(
            (HostedEndpointOwnerPid.parse("123") as Refinement.Refined).value,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )

    @Test
    fun `compatible host retains loaded plugin provenance rather than control release version`() {
        val admission =
            policy.admit(baseline.copy(kastPluginVersion = "0.49.0")) as IdeHostCompatibilityAdmission.Admitted
        assertEquals(
            HostedServiceStatus.Compatible(
                root = "/workspace",
                host = descriptor.host.toString(),
                hostPid = 123,
                hostedPluginVersion = "0.49.0",
            ),
            projectHostedService(HostedServiceObservation.Compatible(root, descriptor, admission.compatibility)),
        )
    }

    @Test
    fun `incompatible host retains identity provenance and the exact contract mismatch`() {
        val admission =
            policy.admit(
                baseline.copy(kastPluginVersion = "0.49.0", runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2")
            ) as IdeHostCompatibilityAdmission.Rejected
        val provenance = HostProvenance((KastPluginVersion.parse("0.49.0") as Refinement.Refined).value)
        assertEquals(
            HostedServiceStatus.Incompatible(
                root = "/workspace",
                host = descriptor.host.toString(),
                hostPid = 123,
                hostedPluginVersion = "0.49.0",
                failure =
                    HostedCompatibilityStatusFailure.Mismatch(
                        HostedCompatibilityStatusField.RUNTIME_PROTOCOL_IDENTITY,
                        listOf("kast.ide-hosted.runtime.v1"),
                        listOf("kast.ide-hosted.runtime.v2"),
                    ),
            ),
            projectHostedService(
                HostedServiceObservation.Incompatible(
                    root = root,
                    descriptor = descriptor,
                    compatibilityFailure = admission.failure,
                    provenance = provenance,
                )
            ),
        )
    }

    @Test
    fun `unavailable host retains finite transport failure without claiming compatibility`() {
        assertEquals(
            HostedServiceStatus.Unavailable("/workspace", HostedServiceUnavailableFailure.DEADLINE_EXCEEDED),
            projectHostedService(HostedServiceObservation.Unavailable(root, ExistingIdeFailure.DEADLINE_EXCEEDED)),
        )
    }
}
