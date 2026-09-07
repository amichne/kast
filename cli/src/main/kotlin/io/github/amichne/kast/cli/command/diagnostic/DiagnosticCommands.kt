package io.github.amichne.kast.cli.command.diagnostic

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest

internal fun diagnosticCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val check = SemanticKastCommand(
        name = "check",
        operation = CanonicalOperation.DIAGNOSTIC_CHECK,
        schemaUsage = "diagnostic check < request.json",
        description = "Check diagnostics from a canonical JSON request on standard input.",
        serializer = DiagnosticCheckRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.diagnosticCheck,
    )
    return CommandFamily(
        KastCommandGroup("diagnostic", "Read compiler diagnostics.").subcommands(check),
        listOf(check),
    )
}
