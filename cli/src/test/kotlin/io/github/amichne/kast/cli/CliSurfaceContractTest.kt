package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CliSurfaceContractTest {
    @Test
    fun `public syntax is exact over all twelve canonical operations`() {
        val surface = commandGraphFactory().surface

        assertEquals(CanonicalOperation.entries, surface.semanticCommands.map { it.operation })
        assertEquals(
            setOf("add-file", "add-declaration", "replace-declaration", "rename-symbol"),
            Regex("add-file|add-declaration|replace-declaration|rename-symbol")
                .findAll(
                    surface.semanticCommands.single {
                        it.operation == CanonicalOperation.CHANGE_PLAN
                    }.usage,
                )
                .map { match -> match.value }
                .toSet(),
        )
        assertEquals(CliLifecycleCommand.entries, surface.lifecycleCommands)
    }

    @Test
    fun `local metadata returns before root or runtime demand`() {
        var boundaryTouched = false
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
            runtimeDemander = RuntimeDemander { _, _ ->
                boundaryTouched = true
                error("runtime demand must not run")
            },
            wireClient = WireClient { _, _ ->
                boundaryTouched = true
                error("wire exchange must not run")
            },
            localMetadata = when (
                val admitted = CliLocalMetadata.admit(
                    productVersion = "1.2.3",
                    schema = "{\"schemaVersion\":1}",
                )
            ) {
                is CliLocalMetadataAdmission.Admitted -> admitted.metadata
                is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
            },
            lifecycle = ExactRootRuntimeLifecycle(),
        )

        val help = cli.execute(listOf("--help"), Path.of("/missing")) as CliExit.Complete
        val version = cli.execute(listOf("--version"), Path.of("/missing")) as CliExit.Complete
        val schema = cli.execute(listOf("--schema"), Path.of("/missing")) as CliExit.Complete

        assertFalse(boundaryTouched)
        assertTrue(help.document.value.contains("workspace"))
        assertTrue(help.document.value.contains("change"))
        CliLifecycleCommand.entries.forEach { command ->
            assertTrue(help.document.value.contains(command.command))
        }
        assertFalse(help.document.value.contains(" setup"))
        assertEquals(
            "kast 1.2.3 (IDE-hosted)",
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
        val admitted = CliOpenJsonObject.parse("{\"future\":{\"value\":null}}")
            as CliOpenJsonObjectAdmission.Admitted
        assertEquals("{\"future\":{\"value\":null}}", admitted.value.document().value)
    }

    @Test
    fun `text admission rejects blank process output as finite data`() {
        assertEquals(
            CliTextDocumentAdmission.Rejected(CliTextDocumentFailure.BLANK),
            CliTextDocument.admit("  "),
        )
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
    }
}
