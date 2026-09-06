package io.github.amichne.kast.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parsers.CommandLineParser
import io.github.amichne.kast.cli.command.projectPublicDefinitions
import io.github.amichne.kast.cli.command.symbol.symbolCommandGroup
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import io.github.amichne.kast.protocol.registry.HostedExposure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CliMixedExposureTest {
    @Test
    fun `mixed family omits internal and unavailable leaves from parser and help registration`() {
        listOf(HostedExposure.INTERNAL_ONLY, HostedExposure.UNAVAILABLE).forEach { exposure ->
            val definitions = CanonicalOperationDefinitions.all.map { definition ->
                if (definition.operation == CanonicalOperation.SYMBOL_INSPECT) definition.copy(hostedExposure = exposure)
                else definition
            }
            val family = symbolCommandGroup(canonicalCliRequestPreparers()).projectPublicDefinitions(definitions)
            assertEquals(listOf("discover"), family.root.registeredSubcommandNames())
            assertEquals(listOf(CanonicalOperation.SYMBOL_DISCOVER), family.semanticCommands.map { it.operation })
            assertThrows(UsageError::class.java) {
                val parsed = CommandLineParser.parse(family.root, listOf("inspect", "--candidate", "candidate"))
                CommandLineParser.run(parsed.invocation) { command -> command.resolveAction() }
            }
        }
    }
}
