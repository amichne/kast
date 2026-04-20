package io.github.amichne.kast.cli.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WalkerCommandTest {
    @Test
    fun `null input is treated as end of stream`() {
        assertEquals(WalkerCommand.EndOfInput, WalkerCommand.parse(null))
    }

    @Test
    fun `blank input is treated as help`() {
        assertEquals(WalkerCommand.Help, WalkerCommand.parse("   "))
    }

    @Test
    fun `quit aliases all map to Quit`() {
        listOf("q", "quit", "exit").forEach { raw ->
            assertEquals(WalkerCommand.Quit, WalkerCommand.parse(raw), "parse($raw)")
        }
    }

    @Test
    fun `back aliases map to Back`() {
        listOf("b", "back").forEach { raw ->
            assertEquals(WalkerCommand.Back, WalkerCommand.parse(raw))
        }
    }

    @Test
    fun `jump commands parse a one-based index`() {
        assertEquals(WalkerCommand.JumpReference(3), WalkerCommand.parse("r 3"))
        assertEquals(WalkerCommand.JumpCaller(1), WalkerCommand.parse("c 1"))
        assertEquals(WalkerCommand.JumpCallee(7), WalkerCommand.parse("o 7"))
    }

    @Test
    fun `grep with explicit n overrides default`() {
        assertEquals(WalkerCommand.GrepComparison(6), WalkerCommand.parse("g"))
        assertEquals(WalkerCommand.GrepComparison(20), WalkerCommand.parse("grep 20"))
    }

    @Test
    fun `unknown commands preserve the raw input`() {
        assertEquals(WalkerCommand.Unknown("jump"), WalkerCommand.parse("jump"))
    }

    @Test
    fun `jump without index is reported as unknown`() {
        assertEquals(WalkerCommand.Unknown("r"), WalkerCommand.parse("r"))
    }
}
