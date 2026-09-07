package io.github.amichne.kast.cli.command.source

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SourceReadRequest

internal fun sourceCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val read = SemanticKastCommand(
        name = "read",
        operation = CanonicalOperation.SOURCE_READ,
        schemaUsage = "source read < request.json",
        description = "Read source from one canonical JSON request on standard input.",
        serializer = SourceReadRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.sourceRead,
    )
    return CommandFamily(
        KastCommandGroup("source", "Read exact structural source context.").subcommands(read),
        listOf(read),
    )
}
