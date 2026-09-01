package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.RuntimeDigest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProductInspectionCommandTest {
    @Test
    fun `product inspection reports sidecar cache and per-socket telemetry without runtime demand`(
        @TempDir temporary: Path,
    ) {
        val rootPath = Files.createDirectory(temporary.resolve("repo"))
        Files.writeString(rootPath.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = (FilesystemCanonicalRootDiscovery.discover(rootPath) as
            CanonicalRootDiscovery.Discovered).root
        val support = SupportedIdeRuntimePair.admit(
            "262.9437.185",
            "262.9437.185-IJ",
        ).let { (it as SupportedIdeRuntimePairAdmission.Admitted).pair }
        val identity = SidecarProductIdentity(
            productVersion = KastPluginVersion.parse("1.2.3").refined(),
            runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refined(),
            supportedRuntime = support,
            payloadDigest = RuntimeDigest.parse("sha256:${"b".repeat(64)}").refined(),
        )
        val endpoint = when (val resolution = RuntimeEndpoint.at(
            root,
            identity.runtimeId,
            temporary.resolve("kast-root.sock"),
        )) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error(resolution.failure)
        }
        val cacheIdentity = "sha256:${"c".repeat(64)}"
        val exactEndpoint = (
            endpoint.forSidecarCache(cacheIdentity, identity.runtimeId) as
                RuntimeEndpointResolution.Resolved
        ).endpoint
        val inspector = SidecarProductInspector(
            identity,
            FilesystemCanonicalRootDiscovery,
            object : RootSidecarCacheLifecycle {
                override fun observe(root: Path): RootSidecarCacheObservation =
                    RootSidecarCacheObservation.Observed(
                        RootSidecarCacheStatus(
                            cacheIdentity,
                            identity.runtimeId,
                            KastCacheState.SMART,
                            temporary.resolve("IntelliJ IDEA.app"),
                            support.ideaBuild,
                            support.kotlinPluginBuild,
                            "jbr-25.0.3-aarch64",
                            identity.payloadDigest.value,
                        ),
                    )

                override fun quarantine(root: Path): RootSidecarCacheQuarantine =
                    error("product inspection must not quarantine")
            },
            RuntimeEndpointLocator { RuntimeEndpointResolution.Resolved(endpoint) },
        )
        var semanticBoundaryTouched = false
        val cli = cli(
            inspector,
            semanticBoundaryTouched = { semanticBoundaryTouched = true },
        )

        val parsed = commandGraphFactory().parse(listOf("product", "inspect"))
        val exit = cli.execute(listOf("product", "inspect"), root.path)

        assertEquals(CliAction.Local.ProductInspect, (parsed as CliCommandParsing.Parsed).action)
        assertFalse(semanticBoundaryTouched)
        val output = Json.parseToJsonElement((exit as CliExit.Complete).document.value).jsonObject
        assertEquals("product.inspect", output.getValue("operation").jsonPrimitive.content)
        assertEquals(
            "isolated-intellij-sidecar",
            output.getValue("control").jsonObject
                .getValue("execution").jsonPrimitive.content,
        )
        val workspace = output.getValue("workspace").jsonObject
        assertEquals(
            cacheIdentity,
            workspace.getValue("cache").jsonObject.getValue("identity").jsonPrimitive.content,
        )
        assertEquals(
            "smart",
            workspace.getValue("cache").jsonObject.getValue("state").jsonPrimitive.content,
        )
        val telemetry = workspace.getValue("telemetry").jsonObject
        assertEquals("enabled", telemetry.getValue("state").jsonPrimitive.content)
        assertEquals("otlp-json-lines-v1", telemetry.getValue("format").jsonPrimitive.content)
        assertEquals(
            "${exactEndpoint.socketPath}.state/otel",
            telemetry.getValue("directoryPath").jsonPrimitive.content,
        )
        assertEquals(
            "${exactEndpoint.socketPath}.state/otel/traces.jsonl",
            telemetry.getValue("traceFilePath").jsonPrimitive.content,
        )
    }

    private fun cli(
        inspector: ProductInspector,
        semanticBoundaryTouched: () -> Unit = {},
    ) = KastCli(
        commandGraphFactory = commandGraphFactory(),
        rootDiscovery = CanonicalRootDiscoverer {
            semanticBoundaryTouched()
            error("semantic root discovery must not run")
        },
        endpointLocator = RuntimeEndpointLocator {
            semanticBoundaryTouched()
            error("runtime endpoint lookup must not run")
        },
        runtimeDemander = RuntimeDemander { _, _ ->
            semanticBoundaryTouched()
            error("runtime demand must not run")
        },
        wireClient = WireClient { _, _ ->
            semanticBoundaryTouched()
            error("wire exchange must not run")
        },
        localMetadata = localMetadata(),
        lifecycle = ExactRootRuntimeLifecycle(),
        productInspector = inspector,
    )

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

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
