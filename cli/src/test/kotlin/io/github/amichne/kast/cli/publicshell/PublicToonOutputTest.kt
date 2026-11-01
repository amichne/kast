package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicToonOutputTest {
    @Test
    fun `encoding rejection is unsuccessful and contains the finite cause`() {
        val output = presentPublicOutput(PublicPreparedOutput("{", PublicShellExit.COMPLETE), CliOutputFormat.TOON)
        assertEquals(PublicShellExit.PROTOCOL, output.exit)
        assertEquals("failure: OUTPUT_ENCODING_REJECTED\ntype: rejected", output.text)
    }

    @Test
    fun `help keeps its plain text without a JSON heuristic`() {
        val help = PublicPreparedOutput("kast query --name VALUE", PublicShellExit.COMPLETE, PublicOutputContent.HELP)
        assertEquals(help, presentPublicOutput(help, CliOutputFormat.TOON))
    }
}
