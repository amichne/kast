package io.github.amichne.kast.cli.command.workspace

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IndexSyncRequest

internal fun indexCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val sync =
        SemanticKastCommand(
            name = "sync",
            operation = CanonicalOperation.INDEX_SYNC,
            schemaUsage = "index sync < request.json",
            description = "Synchronize indexes from a canonical JSON request on standard input.",
            serializer = IndexSyncRequest.serializer(),
            requestInput = requestInput,
            preparer = preparers.indexSync,
        )
    return CommandFamily(
        KastCommandGroup("index", "Synchronize the semantic index.").subcommands(sync),
        listOf(sync),
    )
}
