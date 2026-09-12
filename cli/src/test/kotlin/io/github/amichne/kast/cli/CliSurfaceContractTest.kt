package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliSurfaceContractTest {
    @Test
    fun `public syntax follows canonical exposure while retaining internal identities`() {
        val surface = commandGraphFactory().surface

        assertEquals(
            io.github.amichne.kast.protocol.registry.HostedOperationProjection.publicDefinitions.map { it.operation },
            surface.semanticCommands.map { it.operation },
        )
        assertEquals(
            "change plan < request.json",
            surface.semanticCommands
                .single {
                    it.operation == CanonicalOperation.CHANGE_PLAN
                }
                .usage,
        )
        assertEquals(emptyList<CliLifecycleCommand>(), surface.lifecycleCommands)
        assertEquals(
            listOf(
                CliProductCommand.CODEX_CLI,
                CliProductCommand.CODEX_DESKTOP,
                CliProductCommand.INDEX_STATUS,
                CliProductCommand.INDEX_CLASSES,
                CliProductCommand.INDEX_SUPERTYPE,
                CliProductCommand.INDEX_COMPLETION,
                CliProductCommand.IDE_STATUS,
                CliProductCommand.IDE_CLASSES,
                CliProductCommand.IDE_SUPERTYPE,
                CliProductCommand.IDE_COMPLETION,
                CliProductCommand.IDE_TRUST_BROKER,
                CliProductCommand.APP_SERVER_REGISTER,
                CliProductCommand.APP_SERVER_ENABLE,
                CliProductCommand.APP_SERVER_REPAIR,
                CliProductCommand.APP_SERVER_STATUS,
                CliProductCommand.APP_SERVER_STOP,
                CliProductCommand.APP_SERVER_DISABLE,
                CliProductCommand.APP_SERVER_CLAIM,
                CliProductCommand.APP_SERVER_RELEASE,
            ),
            surface.localCommands,
        )
    }

    @Test
    fun `local metadata returns before root or runtime demand`() {
        var boundaryTouched = false
        val cli =
            KastCli(
                commandGraphFactory = commandGraphFactory(),
                rootDiscovery =
                    CanonicalRootDiscoverer {
                        boundaryTouched = true
                        error("root discovery must not run")
                    },
                localMetadata =
                    when (
                        val admitted =
                            CliLocalMetadata.admit(
                                productVersion = "1.2.3",
                                schema = "{\"schemaVersion\":1}",
                            )
                    ) {
                        is CliLocalMetadataAdmission.Admitted -> admitted.metadata
                        is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
                    },
                productVersion =
                    (io.github.amichne.kast.protocol.contract.KastPluginVersion.parse("1.2.3")
                            as io.github.amichne.kast.kernel.Refinement.Refined)
                        .value,
            )

        val help = cli.execute(listOf("--help"), Path.of("/missing")) as CliExit.Complete
        val version = cli.execute(listOf("--version"), Path.of("/missing")) as CliExit.Complete
        val schema = cli.execute(listOf("--schema"), Path.of("/missing")) as CliExit.Complete
        val helpText = help.document.value

        assertFalse(boundaryTouched)
        assertTrue(helpText.contains("Query the existing IDEA index with index commands"))
        assertTrue(helpText.contains("Show the installed IntelliJ plugin product version"))
        assertTrue(helpText.contains("product"))
        assertTrue(helpText.contains("Read exact semantic relations."))
        assertTrue(helpText.contains("Read compiler diagnostics."))
        assertTrue(helpText.contains("Plan, apply, and recover semantic changes"))
        assertTrue(helpText.contains("Read exact semantic relations"))
        assertTrue(helpText.contains("Read compiler diagnostics"))
        assertTrue(helpText.contains("workspace"))
        assertTrue(helpText.contains("change"))
        listOf(CliLifecycleCommand.START, CliLifecycleCommand.STOP).forEach { command ->
            assertFalse(helpText.lineSequence().any { it.trimStart().startsWith(command.command + " ") })
        }
        assertFalse(helpText.contains(" setup"))
        assertEquals(
            "kast 1.2.3 (IntelliJ plugin)",
            version.document.value,
        )
        assertEquals("{\"schemaVersion\":1}", schema.document.value)
    }

    @Test
    fun `open schema admission remains object shaped and finite on malformed input`() {
        assertEquals(
            CliOpenJsonObjectAdmission.Rejected(CliOpenJsonObjectFailure.NOT_AN_OBJECT),
            CliOpenJsonObject.parse("[]"),
        )
        assertEquals(
            CliOpenJsonObjectAdmission.Rejected(CliOpenJsonObjectFailure.MALFORMED),
            CliOpenJsonObject.parse("{broken"),
        )
        val admitted = CliOpenJsonObject.parse("{\"future\":{\"value\":null}}") as CliOpenJsonObjectAdmission.Admitted
        assertEquals("{\"future\":{\"value\":null}}", admitted.value.document().value)
    }

    @Test
    fun `text admission rejects blank process output as finite data`() {
        assertEquals(
            CliTextDocumentAdmission.Rejected(CliTextDocumentFailure.BLANK),
            CliTextDocument.admit("  "),
        )
    }

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
        }
}
