package io.github.amichne.kast.cli.command.query

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalQueryRequestFragments
import io.github.amichne.kast.protocol.wire.QueryRequestFragmentAdmission

internal fun queryCommandGroup(preparers: CanonicalCliRequestPreparers): CommandFamily {
    val run = QueryRunCommand(preparers)
    return CommandFamily(
        KastCommandGroup("query", "Execute one typed, bounded Kotlin declaration query.")
            .subcommands(run),
        listOf(run),
    )
}

private class QueryRunCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<QueryRunRequest>(
    name = "run",
    operation = CanonicalOperation.QUERY_RUN,
    schemaUsage = "query run --from <json> --steps <json-array> --output <json> --execution <json>",
    preparer = preparers.queryRun,
) {
    private val from by protocolTextOption("--from", "Closed query source JSON.").requiredOnce()
    private val steps by protocolTextOption("--steps", "Closed query step array JSON.").requiredOnce()
    private val output by protocolTextOption("--output", "Closed output projection JSON.").requiredOnce()
    private val execution by protocolTextOption("--execution", "Closed execution policy JSON.").requiredOnce()

    override fun help(context: Context): String =
        "Search, refine, filter, and expand declarations inside one Kast execution."

    override fun resolveAction(): CliActionResolution = when (
        val admission = CanonicalQueryRequestFragments.admit(
            from.value,
            steps.value,
            output.value,
            execution.value,
        )
    ) {
        is QueryRequestFragmentAdmission.Admitted -> prepare(admission.request)
        QueryRequestFragmentAdmission.Rejected ->
            CliActionResolution.UsageRejected(CliUsageFailure.QueryRun.REQUEST_REJECTED)
    }
}
