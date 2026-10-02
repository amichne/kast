package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeDescriptor
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
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
            "262.1.1",
            "262.1.1-IJ",
            "0.50.0",
            "kast.ide-hosted.runtime.v1",
            "sha256:${"a".repeat(64)}",
            "sha256:${"b".repeat(64)}",
            emptyList(),
        )
    private val policy = (IdeHostCompatibilityPolicy.define(baseline) as Refinement.Refined).value
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val descriptor = ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `compatible host retains loaded plugin provenance rather than control release version`() {
        val admission =
            policy.admit(baseline.copy(kastPluginVersion = "0.49.0")) as IdeHostCompatibilityAdmission.Admitted
        assertEquals(
            HostedServiceStatus.Compatible("/workspace", descriptor.host.toString(), 123, "0.49.0"),
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
                "/workspace",
                descriptor.host.toString(),
                123,
                "0.49.0",
                HostedCompatibilityStatusFailure.Mismatch(
                    HostedCompatibilityStatusField.RUNTIME_PROTOCOL_IDENTITY,
                    listOf("kast.ide-hosted.runtime.v1"),
                    listOf("kast.ide-hosted.runtime.v2"),
                ),
            ),
            projectHostedService(
                HostedServiceObservation.Incompatible(root, descriptor, admission.failure, provenance)
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
