package io.github.amichne.kast.cli.command.symbol

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.CliOptionValue
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.closedChoiceOption
import io.github.amichne.kast.cli.command.defaultOnce
import io.github.amichne.kast.cli.command.optionalOnce
import io.github.amichne.kast.cli.command.protocolCountOption
import io.github.amichne.kast.cli.command.protocolOffsetOption
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument

internal fun symbolCommandGroup(
    preparers: CanonicalCliRequestPreparers,
): CommandFamily {
    val commands = listOf(
        SymbolDiscoverCommand(preparers),
        SymbolResolveCommand(preparers),
        SymbolDescribeCommand(preparers),
    )
    return CommandFamily(
        KastCommandGroup("symbol", "Discover, resolve, and describe Kotlin symbols.")
            .subcommands(commands),
        commands,
    )
}

private enum class SymbolDiscoveryMode { NAME, LOCATION, TEXT }

private enum class SymbolTextScope { WORKSPACE, FILE }

private class SymbolDiscoverCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<SymbolDiscoverRequest>(
    name = "discover",
    operation = CanonicalOperation.SYMBOL_DISCOVER,
    schemaUsage = "symbol discover --mode <name|location|text> ... --limit <1..1000>",
    preparer = preparers.symbolDiscover,
) {
    private val mode by closedChoiceOption(
        "--mode",
        "mode",
        "Discovery mode. Defaults to name.",
        linkedMapOf(
            "name" to SymbolDiscoveryMode.NAME,
            "location" to SymbolDiscoveryMode.LOCATION,
            "text" to SymbolDiscoveryMode.TEXT,
        ),
    ).defaultOnce(SymbolDiscoveryMode.NAME, "name")
    private val query by protocolTextOption("--query", "Name or text query.").optionalOnce()
    private val kind by closedChoiceOption(
        "--kind",
        "kind",
        "Name discovery kind. Defaults to symbol.",
        linkedMapOf(
            "file" to SymbolNameKindDocument.FILE,
            "class" to SymbolNameKindDocument.CLASS,
            "symbol" to SymbolNameKindDocument.SYMBOL,
        ),
    ).optionalOnce()
    private val match by closedChoiceOption(
        "--match",
        "match",
        "Name matching policy. Defaults to fuzzy.",
        linkedMapOf(
            "fuzzy" to SymbolDiscoveryMatchDocument.FUZZY,
            "exact-name" to SymbolDiscoveryMatchDocument.EXACT_NAME,
        ),
    ).optionalOnce()
    private val file by protocolTextOption("--file", "Workspace-relative file path.").optionalOnce()
    private val offset by protocolOffsetOption("--offset", "Non-negative source offset.").optionalOnce()
    private val scope by closedChoiceOption(
        "--scope",
        "scope",
        "Text discovery scope.",
        linkedMapOf(
            "workspace" to SymbolTextScope.WORKSPACE,
            "file" to SymbolTextScope.FILE,
        ),
    ).optionalOnce()
    private val limit by protocolCountOption("--limit", "Maximum returned items.").requiredOnce()

    override fun help(context: Context): String =
        "Discover symbols by name, source location, or bounded text search."

    override fun helpEpilog(context: Context): String = """
        Mode contracts:
          name       --query <text> [--kind <file|class|symbol>] [--match <fuzzy|exact-name>]
          location   --file <path> --offset <offset>
          text       --query <text> --scope <workspace|file> [--file <path>]
        Every mode requires --limit <1..1000>.
    """.trimIndent()

    override fun resolveAction(): CliActionResolution = when (
        val refined = SymbolDiscoverCliInput.refine(
            mode,
            query,
            kind,
            match,
            file,
            offset,
            scope,
            limit,
        )
    ) {
        is Refinement.Refined -> prepare(refined.value)
        is Refinement.Rejected -> CliActionResolution.UsageRejected(refined.failure)
    }
}

private object SymbolDiscoverCliInput {
    /**
     * Proof transition: `Clikt symbol options -> Refinement<SymbolDiscoverRequest,
     * CliUsageFailure.SymbolDiscover>`.
     *
     * Establishes exactly the fields admitted by the selected discovery mode and constructs the
     * existing closed protocol target. [CliUsageFailure.SymbolDiscover] closes invalid option
     * combinations. Closed Clikt option-presence states are consumed only at this outer command
     * boundary.
     */
    @Suppress("LongParameterList")
    fun refine(
        mode: SymbolDiscoveryMode,
        query: CliOptionValue<ProtocolText>,
        kind: CliOptionValue<SymbolNameKindDocument>,
        match: CliOptionValue<SymbolDiscoveryMatchDocument>,
        file: CliOptionValue<ProtocolText>,
        offset: CliOptionValue<ProtocolOffset>,
        scope: CliOptionValue<SymbolTextScope>,
        limit: ProtocolCount,
    ): Refinement<SymbolDiscoverRequest, CliUsageFailure.SymbolDiscover> {
        val target = when (mode) {
            SymbolDiscoveryMode.NAME -> {
                if (
                    query !is CliOptionValue.Present || file !is CliOptionValue.Absent ||
                    offset !is CliOptionValue.Absent || scope !is CliOptionValue.Absent
                ) {
                    return rejected(CliUsageFailure.SymbolDiscover.OPTIONS_DO_NOT_MATCH_MODE)
                }
                SymbolDiscoverTargetDocument.Name(
                    query.value,
                    when (kind) {
                        CliOptionValue.Absent -> SymbolNameKindDocument.SYMBOL
                        is CliOptionValue.Present -> kind.value
                    },
                    when (match) {
                        CliOptionValue.Absent -> SymbolDiscoveryMatchDocument.FUZZY
                        is CliOptionValue.Present -> match.value
                    },
                )
            }
            SymbolDiscoveryMode.LOCATION -> {
                if (
                    file !is CliOptionValue.Present || offset !is CliOptionValue.Present ||
                    query !is CliOptionValue.Absent || kind !is CliOptionValue.Absent ||
                    match !is CliOptionValue.Absent || scope !is CliOptionValue.Absent
                ) {
                    return rejected(CliUsageFailure.SymbolDiscover.OPTIONS_DO_NOT_MATCH_MODE)
                }
                SymbolDiscoverTargetDocument.Location(file.value, offset.value)
            }
            SymbolDiscoveryMode.TEXT -> when (
                val refined = textTarget(query, kind, match, file, offset, scope)
            ) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return rejected(refined.failure)
            }
        }
        return Refinement.Refined(SymbolDiscoverRequest(target, limit))
    }

    private fun textTarget(
        query: CliOptionValue<ProtocolText>,
        kind: CliOptionValue<SymbolNameKindDocument>,
        match: CliOptionValue<SymbolDiscoveryMatchDocument>,
        file: CliOptionValue<ProtocolText>,
        offset: CliOptionValue<ProtocolOffset>,
        scope: CliOptionValue<SymbolTextScope>,
    ): Refinement<SymbolDiscoverTargetDocument.Text, CliUsageFailure.SymbolDiscover> {
        if (
            query !is CliOptionValue.Present || kind !is CliOptionValue.Absent ||
            match !is CliOptionValue.Absent || offset !is CliOptionValue.Absent
        ) {
            return rejected(CliUsageFailure.SymbolDiscover.OPTIONS_DO_NOT_MATCH_MODE)
        }
        val refinedScope = when (scope) {
            CliOptionValue.Absent -> return rejected(
                CliUsageFailure.SymbolDiscover.TEXT_SCOPE_REQUIRED,
            )
            is CliOptionValue.Present -> when (scope.value) {
                SymbolTextScope.WORKSPACE -> when (file) {
                    CliOptionValue.Absent -> SymbolTextScopeDocument.Workspace
                    is CliOptionValue.Present -> return rejected(
                        CliUsageFailure.SymbolDiscover.TEXT_FILE_REJECTED,
                    )
                }
                SymbolTextScope.FILE -> when (file) {
                    CliOptionValue.Absent -> return rejected(
                        CliUsageFailure.SymbolDiscover.TEXT_FILE_REQUIRED,
                    )
                    is CliOptionValue.Present -> SymbolTextScopeDocument.File(file.value)
                }
            }
        }
        return Refinement.Refined(SymbolDiscoverTargetDocument.Text(query.value, refinedScope))
    }

    private fun rejected(
        failure: CliUsageFailure.SymbolDiscover,
    ): Refinement.Rejected<CliUsageFailure.SymbolDiscover> = Refinement.Rejected(failure)
}

private class SymbolResolveCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<SymbolResolveRequest>(
    name = "resolve",
    operation = CanonicalOperation.SYMBOL_RESOLVE,
    schemaUsage = "symbol resolve --candidate <candidate-selector>",
    preparer = preparers.symbolResolve,
) {
    private val candidate by protocolTextOption(
        "--candidate",
        "Candidate selector returned by discovery.",
    ).requiredOnce()

    override fun help(context: Context): String = "Resolve one candidate to an exact selector."

    override fun resolveAction(): CliActionResolution = prepare(SymbolResolveRequest(candidate))
}

private class SymbolDescribeCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<SymbolDescribeRequest>(
    name = "describe",
    operation = CanonicalOperation.SYMBOL_DESCRIBE,
    schemaUsage = "symbol describe --selector <exact-selector>",
    preparer = preparers.symbolDescribe,
) {
    private val selector by protocolTextOption("--selector", "Exact symbol selector.").requiredOnce()

    override fun help(context: Context): String = "Describe one exact generation-bound symbol."

    override fun resolveAction(): CliActionResolution = prepare(SymbolDescribeRequest(selector))
}
