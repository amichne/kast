package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityMismatch
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeCompatibilityRejectionTest {
    @Test
    fun `plugin version mismatch reaches the runtime document with exact evidence`() {
        val fixture = ideEndpointFixture()
        val admission = IdeOnlyRuntimeDemander(
            IdeEndpointAdmitter(
                fixture.socketDirectory,
                fixture.policy,
                IdeEndpointDescriptorReader {
                    IdeEndpointDescriptorRead.Complete(
                        fixture.document.replace(FIXTURE_PLUGIN_VERSION, OBSERVED_PLUGIN_VERSION),
                    )
                },
                IdeEndpointProcessProbe { IdeEndpointProcessObservation.Alive },
                IdeEndpointReachabilityProbe { IdeEndpointReachability.Reachable },
            ),
            SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refined(),
        ).demand(
            fixture.root,
            HostedRuntimeDemand.Operation(CanonicalOperation.WORKSPACE_INSPECT),
        )
        val failure = (admission as RuntimeAdmission.Rejected).failure
            as RuntimeAdmissionFailure.IdeDescriptorRejected
        val compatibilityFailure = (
            failure.failure as IdeEndpointDescriptorFailure.CompatibilityRejected
        ).failure as io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Mismatch
        val mismatch = compatibilityFailure.mismatch
            as IdeHostCompatibilityMismatch.KastPluginVersion

        assertEquals(FIXTURE_PLUGIN_VERSION, mismatch.expected.value)
        assertEquals(OBSERVED_PLUGIN_VERSION, mismatch.observed.value)
        assertEquals(
            "{\"status\":\"rejected\",\"boundary\":\"runtime\"," +
                "\"reason\":\"ide-descriptor-rejected\",\"details\":{" +
                "\"type\":\"compatibility-rejected\",\"failure\":{" +
                "\"type\":\"mismatch\",\"field\":\"kast-plugin-version\"," +
                "\"expected\":\"1.2.3\",\"observed\":\"1.2.4\"}}}",
            CliBoundaryDocuments.runtimeRejected(failure).value,
        )
    }
}

private const val OBSERVED_PLUGIN_VERSION = "1.2.4"
