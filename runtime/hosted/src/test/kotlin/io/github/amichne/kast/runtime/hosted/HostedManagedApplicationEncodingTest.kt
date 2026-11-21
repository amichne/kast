package io.github.amichne.kast.runtime.hosted

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedManagedApplicationEncodingTest {
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(checkNotNull(javaClass.getResource("/ide-hosted/hosted-endpoint.schema.json")).readText())
    private val json = Json { encodeDefaults = true }

    @Test
    fun `project endpoint carries managed application facts and rejects desktop ownership`() {
        val document = descriptor()
        val encoded = json.encodeToString(document)
        assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        val obsoleteSchema = json.encodeToString(document.copy(querySchema = "kast.query.run.v9"))
        assertTrue(schema.validate(obsoleteSchema, InputFormat.JSON).isNotEmpty())
        // A Host identity has no mode: a retired desktop or unknown mode member is rejected.
        val withMode =
            encoded.replace("\"autoReload\":\"DISABLED\"", "\"autoReload\":\"DISABLED\",\"mode\":\"DESKTOP\"")
        assertTrue(withMode != encoded)
        assertTrue(schema.validate(withMode, InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `private application advertisement accepts only a bounded Gradle identity`() {
        val delegated = json.encodeToString(descriptor("f".repeat(64)))
        assertTrue(schema.validate(delegated, InputFormat.JSON).isEmpty(), delegated)
        val invalid = json.encodeToString(descriptor("invalid-digest"))
        assertTrue(schema.validate(invalid, InputFormat.JSON).isNotEmpty())
    }

    private fun descriptor(digest: String? = null): HostedEndpointDescriptorDocument {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/work")) as Refinement.Refined).value
        val identity =
            IdeHostRuntimeIdentity(
                "/owned/idea",
                "/owned/state/home/profile",
                42,
                100,
                "00000000-0000-0000-0000-000000000002",
                IdeHostBuildIdentity("0.0.0", "a".repeat(40), "b".repeat(40)),
            )
        val application =
            IdeHostApplicationAdvertisement(
                identity,
                HostedCompatibilityDocument(
                    "262.10315.125",
                    "262.10315.125-IJ",
                    "0.0.0",
                    CanonicalHostedContract.document,
                ),
                digest,
            )
        return HostedEndpointDescriptorDocument.create(
            root,
            Path.of("/owned/host.sock"),
            42,
            "00000000-0000-0000-0000-000000000001",
            HostedEndpointAdvertisement.SEMANTIC,
            application,
        )
    }
}
