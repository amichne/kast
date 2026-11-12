package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedDeploymentAdmissionTest {
    @Test
    fun `owner envelope does not grant current admission to a desktop application`(@TempDir root: Path) {
        val fixture = ManagedDeploymentFixture(root.toRealPath())
        val compatibility =
            HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "1.2.3", CanonicalHostedContract.document)
        val application =
            IdeHostApplicationAdvertisement(
                fixture.identity(UUID(0, 1).toString()).copy(mode = IdeHostMode.DESKTOP),
                compatibility,
            )
        val descriptor =
            HostedSocketExchangeFixture.Descriptor("/workspace", "/unused/host.sock", application = application)
        val bytes = Json { encodeDefaults = true }.encodeToString(descriptor).toByteArray()
        assertInstanceOf(Refinement.Refined::class.java, ExistingIdeDocuments.recordedOwner(bytes))
        assertEquals(
            Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
            ExistingIdeDocuments.declaredEndpoint(bytes),
        )
    }

    @Test
    fun `semantic admission retains managed mode profile process and reload restrictions`(@TempDir root: Path) {
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
                    identity.copy(mode = IdeHostMode.DESKTOP),
                    identity.copy(autoReload = HostAutoReload.ENABLED),
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
