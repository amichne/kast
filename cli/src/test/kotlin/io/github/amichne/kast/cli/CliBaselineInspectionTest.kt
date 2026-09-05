package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliBaselineInspectionTest {
    private fun factory(): CliCommandGraphFactory = when (
        val result = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> result.factory
        is CliCommandGraphConstruction.Rejected -> error("invalid fixture graph: ${result.failures}")
    }

    @Test
    fun `empty invocation selects the existing passive inspection action`() {
        assertEquals(CliCommandParsing.Parsed(CliAction.Local.ProductInspect), factory().parse(emptyList()))
    }

    @Test
    fun `explicit inspection and baseline share one typed action`() {
        val graph = factory()
        assertEquals(graph.parse(listOf("product", "inspect")), graph.parse(emptyList()))
    }

    @Test
    fun `help remains help and a blank argument remains rejected`() {
        val graph = factory()
        assertTrue(graph.parse(listOf("--help")) is CliCommandParsing.Help)
        assertTrue(graph.parse(listOf(" ")) is CliCommandParsing.Rejected)
        assertEquals(CliCommandParsing.Parsed(CliAction.Local.ProductInspect), graph.parse(emptyList()))
    }

    @Test
    fun `an incomplete command never falls through to baseline inspection`() {
        val graph = factory()
        assertTrue(graph.parse(listOf("symbol")) !is CliCommandParsing.Parsed)
        assertTrue(graph.parse(listOf("product")) !is CliCommandParsing.Parsed)
    }
}
