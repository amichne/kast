package io.github.amichne.kast.cli.command.relation

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.RelationReadRequest

internal fun relationCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val read = SemanticKastCommand(
        name = "read",
        operation = CanonicalOperation.RELATION_READ,
        schemaUsage = "relation read < request.json",
        description = "Read one relation from a canonical JSON request on standard input.",
        serializer = RelationReadRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.relationRead,
    )
    return CommandFamily(
        KastCommandGroup("relation", "Read exact semantic relations.").subcommands(read),
        listOf(read),
    )
}
