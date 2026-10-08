package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.ide.ExistingIdeDescriptor
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.HostedEndpointOwnerPid
import io.github.amichne.kast.appserver.ide.canonicalRootFixture
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedProtocolRetirementTest {
    private val root = canonicalRootFixture(Path.of("/workspace"))
    private val descriptor =
        ExistingIdeDescriptor(
            (HostedEndpointOwnerPid.parse("123") as Refinement.Refined).value,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )

    @Test
    fun `old descriptor protocol is unavailable even with otherwise valid metadata`() {
        val raw = HostedDescriptorFixture.endpoint("/workspace", "/tmp/host.sock", descriptor.host, protocol = 2)
        assertEquals(
            Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
            ExistingIdeDocuments.descriptor(raw.toByteArray(), root, Path.of("/tmp/host.sock")),
        )
    }
}
