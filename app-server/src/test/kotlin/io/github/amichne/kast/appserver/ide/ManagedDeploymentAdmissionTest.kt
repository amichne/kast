package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class ManagedDeploymentAdmissionTest {
    @Test
    fun `private launch evidence belongs to the inspected incarnation`(@TempDir root: Path) {
        val fixture = ManagedDeploymentFixture(root.toRealPath())
        val identity = fixture.identity(UUID(0, 3).toString())
        val compatibility =
            HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "1.2.3", CanonicalHostedContract.document)
        val application = IdeHostApplicationAdvertisement(identity, compatibility, "a".repeat(64))
        val inspected =
            IdeLifecycleResult.Inspected(
                identity.hostLifetime,
                identity.ideaHome,
                "262.1.1",
                emptyList(),
                compatibility,
                runtimeIdentity = identity,
            )
        val socket = root.resolve("host.sock")
        val descriptor =
            HostedSocketExchangeFixture.Descriptor(
                identity.ideaHome,
                socket.toString(),
                hostPid = identity.pid,
                host = identity.hostLifetime,
                querySchema = "kast.workspace.lifecycle.v2",
                operations = listOf("WORKSPACE_LIFECYCLE"),
                application = application,
            )
        val recorded =
            assertInstanceOf<Refinement.Refined<RecordedHostedEndpointOwner>>(
                    ExistingIdeDocuments.recordedOwner(
                        Json { encodeDefaults = true }.encodeToString(descriptor).toByteArray()
                    )
                )
                .value
        assertEquals(application, recorded.lifecycleApplication(socket, inspected))
        assertEquals(
            null,
            recorded.lifecycleApplication(
                socket,
                inspected.copy(
                    runtimeIdentity = identity.copy(processStartEpochMillis = identity.processStartEpochMillis + 1)
                ),
            ),
        )
    }

    @Test
    fun `semantic admission retains managed profile and process restrictions`(@TempDir root: Path) {
        val fixture = ManagedDeploymentFixture(root.toRealPath())
        val identity = fixture.identity()
        val owner = (HostedEndpointOwnerPid.parse(identity.pid.toString()) as Refinement.Refined).value
        val compatibility =
            HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "1.2.3", CanonicalHostedContract.document)
        val descriptor =
            ExistingIdeDescriptor(owner, UUID(0, 2), IdeHostApplicationAdvertisement(identity, compatibility))
        assertEquals(Refinement.Refined(descriptor), admitSemanticDeployment(fixture.selected, descriptor))
        val invalid =
            listOf(
                    identity.copy(ideaHome = "/foreign/idea"),
                    identity.copy(profile = "/foreign/profile"),
                    identity.copy(pid = identity.pid + 1),
                    identity.copy(processStartEpochMillis = 0),
                )
                .map {
                    ExistingIdeDescriptor(owner, descriptor.host, IdeHostApplicationAdvertisement(it, compatibility))
                } + ExistingIdeDescriptor(owner, descriptor.host)
        for (candidate in invalid) {
            assertEquals(
                Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
                admitSemanticDeployment(fixture.selected, candidate),
            )
        }
    }
}
