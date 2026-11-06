package io.github.amichne.kast.runtime.hosted

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedManagedApplicationEncodingTest {
    @Test
    fun `project endpoint carries managed application facts and rejects desktop ownership`() {
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(checkNotNull(javaClass.getResource("/ide-hosted/hosted-endpoint.schema.json")).readText())
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/work")) as Refinement.Refined).value
        val identity =
            IdeHostRuntimeIdentity(
                "/owned/idea",
                "/owned/state/home/profile",
                42,
                100,
                "00000000-0000-0000-0000-000000000002",
                IdeHostBuildIdentity("0.0.0", "a".repeat(40), "b".repeat(40)),
                mode = IdeHostMode.MANAGED_HEADLESS,
            )
        val application =
            IdeHostApplicationAdvertisement(
                identity,
                io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument(
                    "262.10315.125",
                    "262.10315.125-IJ",
                    "0.0.0",
                    io.github.amichne.kast.protocol.wire.CanonicalHostedContract.document,
                ),
            )
        val document =
            HostedEndpointDescriptorDocument.create(
                root,
                Path.of("/owned/host.sock"),
                42,
                "00000000-0000-0000-0000-000000000001",
                HostedEndpointAdvertisement.SEMANTIC,
                application,
            )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(document)
        assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        val desktop =
            json.encodeToString(
                document.copy(
                    application = application.copy(runtimeIdentity = identity.copy(mode = IdeHostMode.DESKTOP))
                )
            )
        assertTrue(schema.validate(desktop, InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate(encoded.replace("MANAGED_HEADLESS", "UNPROVEN"), InputFormat.JSON).isNotEmpty())
    }
}
