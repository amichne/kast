package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.BrokerServerRunner
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BrokerServeCommandTest {
    @Test
    fun `internal broker serve delegates to its runner without semantic effects`() {
        var boundaryTouched = false
        var runnerCalled = false
        val cli = KastCli(
            commandGraphFactory = commandGraphFactory(),
            rootDiscovery = CanonicalRootDiscoverer {
                boundaryTouched = true
                error("root discovery must not run")
            },
            endpointLocator = RuntimeEndpointLocator {
                boundaryTouched = true
                error("endpoint lookup must not run")
            },
            runtimeDemander = object : RootRuntimeDemander {
                override fun demand(
                    root: CanonicalRoot,
                    demand: HostedRuntimeDemand,
                    startup: RuntimeStartupRequest,
                ): RuntimeAdmission {
                    boundaryTouched = true
                    error("runtime demand must not run")
                }
            },
            wireClient = WireClient { _, _ ->
                boundaryTouched = true
                error("wire exchange must not run")
            },
            localMetadata = metadata(),
            lifecycle = ExactRootRuntimeLifecycle(),
            productInspector = ProductInspector {
                boundaryTouched = true
                error("product inspection must not run")
            },
            brokerServerRunner = BrokerServerRunner {
                runnerCalled = true
                BrokerServerRun.Stopped
            },
        )

        val exit = cli.execute(listOf("broker", "serve"), Path.of("/missing"))

        assertEquals(
            CliBoundaryDocuments.brokerStopped().value,
            (exit as CliExit.Complete).document.value,
        )
        assertTrue(runnerCalled)
        assertFalse(boundaryTouched)
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
    }

    private fun metadata(): CliLocalMetadata = when (
        val admission = CliLocalMetadata.admit("1.2.3", "{\"schemaVersion\":1}")
    ) {
        is CliLocalMetadataAdmission.Admitted -> admission.metadata
        is CliLocalMetadataAdmission.Rejected -> error(admission.failure)
    }
}
