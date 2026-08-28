package io.github.amichne.kast.cli.command.diagnostic

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.protocolCountOption
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest

internal fun diagnosticCommandGroup(
    preparers: CanonicalCliRequestPreparers,
): CommandFamily {
    val check = DiagnosticCheckCommand(preparers)
    return CommandFamily(
        KastCommandGroup(
            "diagnostic",
            "Canonical command shape; not hosted by the current IDE endpoint.",
        ).subcommands(check),
        listOf(check),
    )
}

private class DiagnosticCheckCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<DiagnosticCheckRequest>(
    name = "check",
    operation = CanonicalOperation.DIAGNOSTIC_CHECK,
    schemaUsage = "diagnostic check --scope <scope> --limit <1..1000>",
    preparer = preparers.diagnosticCheck,
) {
    private val scope by protocolTextOption(
        "--scope",
        "Workspace-relative diagnostic scope.",
    ).requiredOnce()
    private val limit by protocolCountOption(
        "--limit",
        "Maximum returned diagnostics.",
    ).requiredOnce()

    override fun help(context: Context): String = "Check diagnostics within one explicit scope."

    override fun resolveAction(): CliActionResolution =
        prepare(DiagnosticCheckRequest(scope, limit))
}
