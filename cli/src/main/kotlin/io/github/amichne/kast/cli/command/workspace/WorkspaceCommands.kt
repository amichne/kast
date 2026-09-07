package io.github.amichne.kast.cli.command.workspace

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest

internal fun topologyCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val build = SemanticKastCommand(
        name = "build",
        operation = CanonicalOperation.TOPOLOGY_BUILD,
        schemaUsage = "topology build < request.json",
        description = "Build topology from a canonical JSON request on standard input.",
        serializer = TopologyBuildRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.topologyBuild,
    )
    return CommandFamily(
        KastCommandGroup("topology", "Build semantic topology.").subcommands(build),
        listOf(build),
    )
}
