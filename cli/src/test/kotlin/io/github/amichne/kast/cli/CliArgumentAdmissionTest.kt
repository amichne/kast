package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CliArgumentAdmissionTest {
    @Test
    fun `ordinary argv remains bounded`() {
        val factory =
            when (val result = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
                is CliCommandGraphConstruction.Created -> result.factory
                is CliCommandGraphConstruction.Rejected -> error("command graph rejected")
            }
        val long =
            assertInstanceOf(
                CliCommandParsing.Rejected::class.java,
                factory.parse(listOf("symbol", "discover", "x".repeat(4_097))),
            )
        assertEquals(CliCommandFailure.ARGUMENT_TOO_LONG, long.failure)
        val many = assertInstanceOf(CliCommandParsing.Rejected::class.java, factory.parse(List(67) { "word" }))
        assertEquals(CliCommandFailure.TOO_MANY_ARGUMENTS, many.failure)
    }
}
