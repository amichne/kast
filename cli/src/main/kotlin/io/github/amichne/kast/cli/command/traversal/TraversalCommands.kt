package io.github.amichne.kast.cli.command.traversal

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliOptionValue
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.protocolCountOption
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.optionalOnce
import io.github.amichne.kast.cli.command.relation.relationOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.kernel.Refinement

internal fun traversalCommandGroup(
    preparers: CanonicalCliRequestPreparers,
): CommandFamily {
    val run = TraversalRunCommand(preparers)
    return CommandFamily(
        KastCommandGroup(
            "traversal",
            "Traverse bounded, durable, generation-bound semantic relation graphs.",
        ).subcommands(run),
        listOf(run),
    )
}

private class TraversalRunCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<TraversalRunRequest>(
    name = "run",
    operation = CanonicalOperation.TRAVERSAL_RUN,
    schemaUsage = "traversal run --selector <exact-selector> --relation <kind> " +
        "--maximum-depth <1..1000> --maximum-results <1..1000>",
    preparer = preparers.traversalRun,
) {
    private val selector by protocolTextOption("--selector", "Exact starting selector.").requiredOnce()
    private val relation by relationOption().requiredOnce()
    private val maximumDepth by protocolCountOption(
        "--maximum-depth",
        "Maximum traversal depth.",
    ).requiredOnce()
    private val maximumResults by protocolCountOption(
        "--maximum-results",
        "Maximum returned symbols.",
    ).requiredOnce()
    private val continuation by protocolTextOption(
        "--continuation",
        "Continuation returned by an earlier resumable traversal.",
    ).optionalOnce()

    override fun help(context: Context): String =
        "Traverse one relation with explicit depth and result budgets."

    override fun resolveAction(): CliActionResolution {
        val position = when (val supplied = continuation) {
            CliOptionValue.Absent -> TraversalRunPositionDocument.Start
            is CliOptionValue.Present -> when (
                val admitted = TraversalContinuationDocument.parse(supplied.value.value)
            ) {
                is Refinement.Refined -> TraversalRunPositionDocument.Resume(admitted.value)
                is Refinement.Rejected -> return CliActionResolution.UsageRejected(
                    CliUsageFailure.TraversalRun.CONTINUATION_REJECTED,
                )
            }
        }
        return prepare(
            TraversalRunRequest(selector, relation, maximumDepth, maximumResults, position),
        )
    }
}
