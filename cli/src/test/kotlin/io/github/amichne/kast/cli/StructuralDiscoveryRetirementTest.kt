package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class StructuralDiscoveryRetirementTest {
    @Test
    fun `legacy structure discovery is rejected and absent from command help`() {
        val factory = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error(
                "command graph: ${construction.failures}",
            )
        }

        assertInstanceOf(
            CliCommandParsing.Rejected::class.java,
            factory.parse(
                listOf(
                    "symbol",
                    "discover",
                    "--mode=structure",
                    "--file=A.kt",
                    "--limit=10",
                ),
            ),
        )
        val help = factory.parse(listOf("symbol", "discover", "--help"))
        assertFalse(help.toString().contains("structure", ignoreCase = true))
    }
}
