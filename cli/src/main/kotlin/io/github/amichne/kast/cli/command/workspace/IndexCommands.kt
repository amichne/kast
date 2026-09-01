package io.github.amichne.kast.cli.command.workspace

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IndexSyncRequest

internal fun indexCommandGroup(preparers: CanonicalCliRequestPreparers): CommandFamily {
    val sync = IndexSyncCommand(preparers)
    return CommandFamily(
        KastCommandGroup("index", "Refresh admitted source roots and synchronize semantic evidence.")
            .subcommands(sync),
        listOf(sync),
    )
}

private class IndexSyncCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<IndexSyncRequest>(
    name = "sync",
    operation = CanonicalOperation.INDEX_SYNC,
    schemaUsage = "index sync",
    preparer = preparers.indexSync,
) {
    override fun help(context: Context): String =
        "Refresh stale admitted files, wait for indexing, and publish current semantic evidence."

    override fun resolveAction(): CliActionResolution = prepare(IndexSyncRequest)
}
