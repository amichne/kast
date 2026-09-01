package io.github.amichne.kast.cli.command.relation

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.closedChoiceOption
import io.github.amichne.kast.cli.command.protocolCountOption
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest

internal fun relationCommandGroup(
    preparers: CanonicalCliRequestPreparers,
): CommandFamily {
    val read = RelationReadCommand(preparers)
    return CommandFamily(
        KastCommandGroup(
            "relation",
            "Read compiler-grounded semantic relations from exact symbols.",
        ).subcommands(read),
        listOf(read),
    )
}

/**
 * Proof transition: `String option -> RelationKindDocument option`.
 *
 * Establishes one canonical relation kind. Unknown text becomes a closed Clikt usage rejection;
 * raw option text is extracted only by the shared Clikt choice converter.
 */
internal fun com.github.ajalt.clikt.core.ParameterHolder.relationOption() = closedChoiceOption(
    "--relation",
    "kind",
    "Relation kind.",
    linkedMapOf(
        "references" to RelationKindDocument.REFERENCES,
        "callers" to RelationKindDocument.CALLERS,
        "callees" to RelationKindDocument.CALLEES,
        "implementations" to RelationKindDocument.IMPLEMENTATIONS,
        "inheritors" to RelationKindDocument.INHERITORS,
        "overrides" to RelationKindDocument.OVERRIDES,
        "type-uses" to RelationKindDocument.TYPE_USES,
    ),
)

private class RelationReadCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<RelationReadRequest>(
    name = "read",
    operation = CanonicalOperation.RELATION_READ,
    schemaUsage =
        "relation read --selector <exact-selector> --relation <kind> --limit <1..1000>",
    preparer = preparers.relationRead,
) {
    private val selector by protocolTextOption("--selector", "Exact symbol selector.").requiredOnce()
    private val relation by relationOption().requiredOnce()
    private val limit by protocolCountOption("--limit", "Maximum returned targets.").requiredOnce()

    override fun help(context: Context): String = "Read one bounded relation from an exact symbol."

    override fun resolveAction(): CliActionResolution =
        prepare(RelationReadRequest(selector, relation, limit))
}
