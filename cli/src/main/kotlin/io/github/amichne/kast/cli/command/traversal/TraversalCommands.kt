package io.github.amichne.kast.cli.command.traversal

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.TraversalRunRequest

internal fun traversalCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val run =
        SemanticKastCommand(
            name = "run",
            operation = CanonicalOperation.TRAVERSAL_RUN,
            schemaUsage = "traversal run < request.json",
            description = "Run one traversal from a canonical JSON request on standard input.",
            serializer = TraversalRunRequest.serializer(),
            requestInput = requestInput,
            preparer = preparers.traversalRun,
        )
    return CommandFamily(
        KastCommandGroup("traversal", "Traverse exact semantic relations.").subcommands(run),
        listOf(run),
    )
}
