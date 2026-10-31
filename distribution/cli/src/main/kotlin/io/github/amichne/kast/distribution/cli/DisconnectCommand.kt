package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert

internal class DisconnectCommand : ManagementNode("disconnect") {
    private val harness by
        argument("harness").convert { raw ->
            Harness.parse(raw) ?: fail("Supported harnesses: codex, copilot, pi")
        }

    override fun help(context: Context) = "Remove a registration owned by this installation."

    override fun selection() = ManagementCommand.Disconnect(harness)
}
