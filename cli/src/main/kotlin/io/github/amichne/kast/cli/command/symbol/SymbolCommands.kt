package io.github.amichne.kast.cli.command.symbol

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest

internal fun symbolCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
): CommandFamily {
    val discover = SemanticKastCommand(
        name = "discover",
        operation = CanonicalOperation.SYMBOL_DISCOVER,
        schemaUsage = "symbol discover < request.json",
        description = "Discover candidates from one canonical JSON request on standard input.",
        serializer = SymbolDiscoverRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.symbolDiscover,
    )
    val inspect = SemanticKastCommand(
        name = "inspect",
        operation = CanonicalOperation.SYMBOL_INSPECT,
        schemaUsage = "symbol inspect < request.json",
        description = "Inspect one symbol from a canonical JSON request on standard input.",
        serializer = SymbolInspectRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.symbolInspect,
    )
    return CommandFamily(
        KastCommandGroup("symbol", "Discover candidates and inspect exact Kotlin symbols.")
            .subcommands(discover, inspect),
        listOf(discover, inspect),
    )
}
