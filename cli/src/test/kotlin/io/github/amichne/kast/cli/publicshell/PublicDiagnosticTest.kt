package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicDiagnosticTest {
    @Test
    fun `diagnostic check admits the existing diagnostic tool without query wrapping`() {
        val parsed =
            parsePublicCliArguments(
                listOf("diagnostic", "check", "--path", "src/main/kotlin", "--max-diagnostics", "7")
            )
        assertTrue(parsed is Refinement.Refined, parsed.toString())
        val command = (parsed as Refinement.Refined).value as PublicCliCommand.Query
        val input = (command.input as PublicCliInput.Lowered).arguments
        assertEquals("src/main/kotlin", input.getValue("relativePath").jsonPrimitive.content)
        assertEquals("7", input.getValue("maxDiagnostics").jsonPrimitive.content)
        val admitted = PublicToolContract.admit(PublicToolIdentity.CHECK_DIAGNOSTICS, input, command.catalog)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        assertTrue((admitted as Refinement.Refined).value.canonical is PublicToolCanonical.Diagnostics)
    }

    @Test
    fun `diagnostics require explicit scope and reject query options`() {
        for (arguments in
            listOf(
                listOf("diagnostic", "check"),
                listOf("diagnostic", "check", "--path", ".", "--name", "A"),
                listOf("diagnostic", "check", "--path", ".", "--max-diagnostics", "seven"),
            )) assertTrue(parsePublicCliArguments(arguments) is Refinement.Rejected, arguments.toString())
    }
}
