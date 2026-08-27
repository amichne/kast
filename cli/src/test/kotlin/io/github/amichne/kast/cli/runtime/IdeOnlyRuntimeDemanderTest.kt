package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IdeOnlyRuntimeDemanderTest {
    @Test
    @DisplayName("Missing or incompatible IDE endpoint evidence is a closed rejection.")
    fun `missing or incompatible IDE endpoint evidence is a closed rejection`() {
        val fixture = ideEndpointFixture()
        val runtimeId = fixtureRuntimeId()
        val missing = IdeOnlyRuntimeDemander(
            fixture.admitter(
                IdeEndpointDescriptorRead.Rejected(IdeEndpointDescriptorReadFailure.UNAVAILABLE),
            ),
            runtimeId,
        ).demand(fixture.root)
        val incompatible = IdeOnlyRuntimeDemander(
            fixture.admitter(
                IdeEndpointDescriptorRead.Complete(
                    fixture.document.replace(FIXTURE_IDE_BUILD, "262.9437.186"),
                ),
            ),
            runtimeId,
        ).demand(fixture.root)

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.IDE_ENDPOINT_REJECTED),
            missing,
        )
        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.IDE_ENDPOINT_REJECTED),
            incompatible,
        )
    }

    @Test
    @DisplayName("The compatible exact-root IDE socket is the sole wire endpoint.")
    fun `the compatible exact-root IDE socket is the sole wire endpoint`() {
        val fixture = ideEndpointFixture()
        val runtimeId = fixtureRuntimeId()

        val admission = IdeOnlyRuntimeDemander(
            fixture.admitter(IdeEndpointDescriptorRead.Complete(fixture.document)),
            runtimeId,
        ).demand(fixture.root)

        val endpoint = (admission as RuntimeAdmission.Ready).endpoint
        assertSame(fixture.root, endpoint.root)
        assertEquals(runtimeId, endpoint.runtimeId)
        assertEquals(fixture.location.socketPath.value, endpoint.socketPath.toString())
    }
}

private fun IdeEndpointFixture.admitter(
    descriptorRead: IdeEndpointDescriptorRead,
): IdeEndpointAdmitter = IdeEndpointAdmitter(
    socketDirectory,
    policy,
    IdeEndpointDescriptorReader { descriptorRead },
    IdeEndpointProcessProbe { IdeEndpointProcessObservation.Alive },
    IdeEndpointReachabilityProbe { IdeEndpointReachability.Reachable },
)

private fun fixtureRuntimeId(): SemanticRuntimeId =
    SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refined()
