package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.BrokerServerRunner
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrokerServeCommandTest {
    @Test
    fun `internal broker serve delegates to its runner without semantic effects`() {
        var boundaryTouched = false
        var runnerCalled = false
        val cli =
            KastCli(
                commandGraphFactory = commandGraphFactory(),
                rootDiscovery =
                    CanonicalRootDiscoverer {
                        boundaryTouched = true
                        error("root discovery must not run")
                    },
                localMetadata = metadata(),
                brokerServerRunner =
                    BrokerServerRunner {
                        runnerCalled = true
                        BrokerServerRun.Stopped
                    },
                productVersion =
                    (io.github.amichne.kast.protocol.contract.KastPluginVersion.parse("1.2.3")
                            as io.github.amichne.kast.kernel.Refinement.Refined)
                        .value,
            )

        val exit = cli.execute(listOf("broker", "serve"), Path.of("/missing"))

        assertEquals(
            CliBoundaryDocuments.brokerStopped().value,
            (exit as CliExit.Complete).document.value,
        )
        assertTrue(runnerCalled)
        assertFalse(boundaryTouched)
    }

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
        }

    private fun metadata(): CliLocalMetadata =
        when (val admission = CliLocalMetadata.admit("1.2.3", "{\"schemaVersion\":1}")) {
            is CliLocalMetadataAdmission.Admitted -> admission.metadata
            is CliLocalMetadataAdmission.Rejected -> error(admission.failure)
        }
}
