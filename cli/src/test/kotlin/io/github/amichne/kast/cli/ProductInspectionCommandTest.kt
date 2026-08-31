package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProductInspectionCommandTest {
    @Test
    fun `product inspection reports an incompatible endpoint without runtime admission`() {
        val fixture = ideEndpointFixture()
        var semanticBoundaryTouched = false
        val inspector = InstalledProductInspector(
            control = fixture.policy.supportedCompatibility,
            rootDiscovery = CanonicalRootDiscoverer {
                CanonicalRootDiscovery.Discovered(fixture.root)
            },
            endpointAdmitter = IdeEndpointAdmitter(
                fixture.socketDirectory,
                fixture.policy,
                IdeEndpointDescriptorReader {
                    IdeEndpointDescriptorRead.Complete(
                        fixture.document.replace(FIXTURE_PLUGIN_VERSION, "1.2.4"),
                    )
                },
                IdeEndpointProcessProbe { IdeEndpointProcessObservation.Alive },
                IdeEndpointReachabilityProbe { IdeEndpointReachability.Reachable },
            ),
        )
        val cli = KastCli(
            commandGraphFactory = commandGraphFactory(),
            rootDiscovery = CanonicalRootDiscoverer {
                semanticBoundaryTouched = true
                error("semantic root discovery must not run")
            },
            endpointLocator = RuntimeEndpointLocator {
                semanticBoundaryTouched = true
                error("runtime endpoint lookup must not run")
            },
            runtimeDemander = RuntimeDemander { _, _ ->
                semanticBoundaryTouched = true
                error("runtime demand must not run")
            },
            wireClient = WireClient { _, _ ->
                semanticBoundaryTouched = true
                error("wire exchange must not run")
            },
            localMetadata = localMetadata(),
            lifecycle = ExactRootRuntimeLifecycle(),
            productInspector = inspector,
        )

        val parsed = commandGraphFactory().parse(listOf("product", "inspect"))
        val exit = cli.execute(listOf("product", "inspect"), Path.of("/unobserved"))

        assertEquals(
            CliAction.Local.ProductInspect,
            (parsed as CliCommandParsing.Parsed).action,
        )
        assertFalse(semanticBoundaryTouched)
        val output = Json.parseToJsonElement((exit as CliExit.Complete).document.value).jsonObject
        assertEquals("product.inspect", output.getValue("operation").jsonPrimitive.content)
        assertEquals("complete", output.getValue("status").jsonPrimitive.content)
        assertEquals(
            FIXTURE_PLUGIN_VERSION,
            output.getValue("control").jsonObject
                .getValue("kastPluginVersion").jsonPrimitive.content,
        )
        val mismatch = output.getValue("workspace").jsonObject
            .getValue("endpoint").jsonObject
            .getValue("failure").jsonObject
            .getValue("failure").jsonObject
            .getValue("failure").jsonObject
        assertEquals("mismatch", mismatch.getValue("type").jsonPrimitive.content)
        assertEquals("kast-plugin-version", mismatch.getValue("field").jsonPrimitive.content)
        assertEquals("1.2.3", mismatch.getValue("expected").jsonPrimitive.content)
        assertEquals("1.2.4", mismatch.getValue("observed").jsonPrimitive.content)
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(construction.failures)
    }

    private fun localMetadata(): CliLocalMetadata = when (
        val admission = CliLocalMetadata.admit("1.2.3", "{\"schemaVersion\":1}")
    ) {
        is CliLocalMetadataAdmission.Admitted -> admission.metadata
        is CliLocalMetadataAdmission.Rejected -> error(admission.failure)
    }
}
