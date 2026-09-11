package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SemanticRuntimeDemandTest {
    @Test
    fun `semantic requests carry their original demand through admission before exchange`(@TempDir path: Path) {
        Files.writeString(path.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val runtimeId = (SemanticRuntimeId.parse("sha256:${"a".repeat(64)}") as Refinement.Refined).value
        val endpoint =
            (RuntimeEndpoint.at(root, runtimeId, path.resolve("runtime.sock")) as RuntimeEndpointResolution.Resolved)
                .endpoint
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val commands =
            listOf(
                listOf("symbol", "discover") to
                    """{"target":{"type":"name","query":"Example","kind":"symbol","match":"fuzzy"},"limit":10}""",
                listOf("change", "plan") to
                    """{"intent":{"kind":"add-file","relativePath":"A.kt","content":"class A"}}""",
            )
        val demanded = mutableListOf<HostedRuntimeDemand>()
        var exchanges = 0
        val cli =
            KastCli(
                commandGraphFactory = graph,
                rootDiscovery = FilesystemCanonicalRootDiscovery,
                endpointLocator = RuntimeEndpointLocator { error("semantic requests must use admitted endpoint") },
                runtimeDemander =
                    RootRuntimeDemander { discovered, demand, startup ->
                        assertEquals(root, discovered)
                        assertEquals(RuntimeStartupRequest.Default, startup)
                        demanded += demand
                        RuntimeAdmission.Ready(endpoint)
                    },
                wireClient =
                    WireClient { admitted, _ ->
                        assertEquals(endpoint, admitted)
                        exchanges += 1
                        WireExchange.Rejected(WireTransportFailure.CONNECTION_FAILED)
                    },
                localMetadata = (CliLocalMetadata.admit("test", "{}") as CliLocalMetadataAdmission.Admitted).metadata,
                lifecycle =
                    object : RuntimeLifecycleController {
                        override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult =
                            error("admission owns readiness")

                        override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult =
                            error("semantic requests cannot stop")
                    },
                productInspector = ProductInspector { error("inspection is passive only") },
            )
        // Repeat the same invocation to prove admission remains the sole owner on reuse.
        (commands + listOf(commands.first())).forEach { (argv, document) ->
            val input = CliRequestDocumentInput.Provided(document)
            val expected =
                ((graph.parse(argv, input) as CliCommandParsing.Parsed).action as CliAction.Semantic)
                    .request
                    .hostedDemand
            val exit = assertInstanceOf(CliExit.BoundaryRejected::class.java, cli.execute(argv, path, input))
            assertEquals(CliBoundaryExitStatus.TRANSPORT, exit.status)
            assertEquals(expected, demanded.last())
        }
        assertEquals(3, exchanges)
        assertEquals(3, demanded.size)
    }
}
