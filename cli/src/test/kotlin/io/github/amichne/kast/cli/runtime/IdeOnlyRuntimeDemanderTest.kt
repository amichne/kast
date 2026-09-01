package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IdeOnlyRuntimeDemanderTest {
    private val workspaceDemand = HostedRuntimeDemand.Operation(
        CanonicalOperation.WORKSPACE_INSPECT,
    )

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
        ).demand(fixture.root, workspaceDemand)
        val incompatible = IdeOnlyRuntimeDemander(
            fixture.admitter(
                IdeEndpointDescriptorRead.Complete(
                    fixture.document.replace(FIXTURE_IDE_BUILD, "262.9437.186"),
                ),
            ),
            runtimeId,
        ).demand(fixture.root, workspaceDemand)

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.IdeDescriptorReadRejected),
            missing,
        )
        assertEquals(
            RuntimeAdmissionFailure.IdeDescriptorRejected::class,
            (incompatible as RuntimeAdmission.Rejected).failure::class,
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
        ).demand(fixture.root, workspaceDemand)

        val endpoint = (admission as RuntimeAdmission.Ready).endpoint
        assertSame(fixture.root, endpoint.root)
        assertEquals(runtimeId, endpoint.runtimeId)
        assertEquals(fixture.location.socketPath.value, endpoint.socketPath.toString())
    }

    @Test
    fun `pre-dispatch capability admission admits public reads and rejects unavailable plan intents`() {
        val fixture = ideEndpointFixture()
        val demander = IdeOnlyRuntimeDemander(
            fixture.admitter(IdeEndpointDescriptorRead.Complete(fixture.document)),
            fixtureRuntimeId(),
        )

        assertEquals(
            RuntimeAdmission.Ready::class,
            demander.demand(
                fixture.root,
                HostedRuntimeDemand.Operation(CanonicalOperation.RELATION_READ),
            )::class,
        )
        assertEquals(
            RuntimeAdmission.Ready::class,
            demander.demand(
                fixture.root,
                HostedRuntimeDemand.Operation(CanonicalOperation.DIAGNOSTIC_CHECK),
            )::class,
        )
        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.IdeVariantUnavailable),
            demander.demand(
                fixture.root,
                HostedRuntimeDemand.ChangePlan(
                    ChangeIntentDocument.RenameSymbol(text("exact:Target"), text("Renamed")),
                ),
            ),
        )
        assertEquals(
            RuntimeAdmission.Ready::class,
            demander.demand(
                fixture.root,
                HostedRuntimeDemand.ChangePlan(
                    ChangeIntentDocument.AddDeclaration(
                        text("exact:Target"),
                        text("fun added() = Unit"),
                    ),
                ),
            )::class,
        )
    }
}

private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

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
