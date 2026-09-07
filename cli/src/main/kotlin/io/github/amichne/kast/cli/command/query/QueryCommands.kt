package io.github.amichne.kast.cli.command.query

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryRunRequest

internal fun queryCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val run = SemanticKastCommand(
        name = "run",
        operation = CanonicalOperation.QUERY_RUN,
        schemaUsage = "query run < request.json",
        description = "Execute one canonical typed query request from standard input.",
        serializer = QueryRunRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.queryRun,
    )
    return CommandFamily(
        KastCommandGroup("query", "Execute one typed, bounded Kotlin declaration query.")
            .subcommands(run),
        listOf(run),
    )
}
