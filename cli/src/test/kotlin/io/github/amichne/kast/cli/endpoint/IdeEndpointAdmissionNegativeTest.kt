package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IdeEndpointAdmissionNegativeTest {
    @Suppress("unused")
    private fun retainCapability(endpoint: AdmittedIdeEndpoint): AdmittedIdeEndpoint = endpoint

    @Test
    @DisplayName(
        "Wrong root, build, schema, PID, runtime, capability, or unreachable endpoint is admitted.",
    )
    fun `wrong root build schema PID runtime capability or unreachable endpoint is rejected`() {
        val fixture = ideEndpointFixture()

        assertFailure(
            fixture.copy(document = ideEndpointFixture(descriptorRoot = "/workspace/other").document),
            IdeEndpointAdmissionFailure.RootMismatch,
        )
        assertDescriptorRejected(fixture.document.replace("kast.ide.endpoint.v2", "invalid"))
        assertCompatibilityRejected(
            fixture,
            fixture.document.replace(FIXTURE_IDE_BUILD, "261.9999.1"),
        )
        assertCompatibilityRejected(
            fixture,
            fixture.document.replace(FIXTURE_RUNTIME_PROTOCOL, "kast.ide-hosted.runtime.v2"),
        )
        assertCompatibilityRejected(
            fixture,
            fixture.document.replace(
                "\"symbol.describe\"",
                "\"diagnostic.check\"",
            ),
        )
        assertFailure(
            fixture,
            IdeEndpointAdmissionFailure.ProcessUnavailable,
            process = IdeEndpointProcessObservation.Absent,
        )
        assertFailure(
            fixture,
            IdeEndpointAdmissionFailure.EndpointUnreachable,
            reachability = IdeEndpointReachability.Unreachable,
        )
        assertFailure(
            ideEndpointFixture(socketPath = "/tmp/unowned.sock"),
            IdeEndpointAdmissionFailure.SocketMismatch,
        )
    }

    private fun assertDescriptorRejected(document: String) {
        val fixture = ideEndpointFixture()
        val result = admit(fixture.copy(document = document))
        require(result is IdeEndpointAdmission.Rejected)
        assertTrue(result.failure is IdeEndpointAdmissionFailure.DescriptorRejected)
    }

    private fun assertCompatibilityRejected(fixture: IdeEndpointFixture, document: String) {
        val result = admit(fixture.copy(document = document))
        require(result is IdeEndpointAdmission.Rejected)
        assertTrue(result.failure is IdeEndpointAdmissionFailure.DescriptorRejected)
    }

    private fun assertFailure(
        fixture: IdeEndpointFixture,
        expected: IdeEndpointAdmissionFailure,
        process: IdeEndpointProcessObservation = IdeEndpointProcessObservation.Alive,
        reachability: IdeEndpointReachability = IdeEndpointReachability.Reachable,
    ) {
        assertEquals(expected, (admit(fixture, process, reachability) as IdeEndpointAdmission.Rejected).failure)
    }

    private fun admit(
        fixture: IdeEndpointFixture,
        process: IdeEndpointProcessObservation = IdeEndpointProcessObservation.Alive,
        reachability: IdeEndpointReachability = IdeEndpointReachability.Reachable,
    ): IdeEndpointAdmission = IdeEndpointAdmitter(
        fixture.socketDirectory,
        fixture.policy,
        IdeEndpointDescriptorReader { IdeEndpointDescriptorRead.Complete(fixture.document) },
        IdeEndpointProcessProbe { process },
        IdeEndpointReachabilityProbe { reachability },
    ).admit(fixture.root)
}
