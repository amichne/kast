package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliProjections
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CliSurfaceContractTest {
    @Test
    fun `public syntax is exact over all eleven canonical operations`() {
        assertEquals(CanonicalOperation.entries, canonicalCliSyntaxes.map { it.operation })
        canonicalCliSyntaxes.forEach { syntax ->
            val parsed = CliCommandParser.parse(syntax.command)
            assertTrue(parsed is CliCommandParsing.Parsed)
            assertEquals(
                syntax.operation,
                (parsed as CliCommandParsing.Parsed).invocation.operation,
            )
        }
        assertEquals(
            setOf("add-file", "add-declaration", "replace-declaration", "rename-symbol"),
            Regex("add-file|add-declaration|replace-declaration|rename-symbol")
                .findAll(canonicalCliSyntaxes.single { it.operation == CanonicalOperation.CHANGE_PLAN }.usage)
                .map { match -> match.value }
                .toSet(),
        )
    }

    @Test
    fun `local metadata returns before root or runtime demand`() {
        var boundaryTouched = false
        val cli = KastCli(
            projections = errorProjectionTable(),
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
                    runtimeIdentity = "sha256:${"a".repeat(64)}",
                    schema = "{\"schemaVersion\":1}",
                )
            ) {
                is CliLocalMetadataAdmission.Admitted -> admitted.metadata
                is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
            },
        )

        val help = cli.execute(listOf("--help"), Path.of("/missing")) as CliExit.Complete
        val version = cli.execute(listOf("--version"), Path.of("/missing")) as CliExit.Complete
        val schema = cli.execute(listOf("--schema"), Path.of("/missing")) as CliExit.Complete

        assertFalse(boundaryTouched)
        assertTrue(help.document.value.contains("workspace inspect"))
        assertTrue(help.document.value.contains("change recover"))
        CliLifecycleCommand.entries.forEach { command ->
            assertTrue(help.document.value.contains("  ${command.command}"))
        }
        assertFalse(help.document.value.contains(" setup"))
        assertEquals(
            "kast 1.2.3 (semantic runtime sha256:${"a".repeat(64)})",
            version.document.value,
        )
        assertEquals("{\"schemaVersion\":1}", schema.document.value)
    }

    private fun errorProjectionTable(): CliProjectionTable = when (
        val construction = CliProjectionTable.create(canonicalCliProjections())
    ) {
        is CliProjectionTableConstruction.Created -> construction.table
        is CliProjectionTableConstruction.Rejected -> error("projection table: ${construction.failures}")
    }
}
