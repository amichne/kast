package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueSite

internal fun reviewedReadFailure(
    site: ValueSite,
    read: ValueFlowRead,
    budget: RelationBudget,
    previous: List<ValueFlowStep>,
): String {
    val result = if (read is ValueFlowRead.Observed) stepSummary(read.step) else read.toString()
    val history = previous.takeLast(DIAGNOSTIC_HISTORY_STEPS).joinToString("\n", transform = ::stepSummary)
    return "Native read grantBytes=${budget.returnedBytes.value} source=${siteSummary(site)} result=$result\n" +
        "Previous bounded steps:\n$history"
}

private fun stepSummary(step: ValueFlowStep): String {
    val targets =
        (step.transfers.map { it.target } + step.reviewedTransfers.map { it.target }).joinToString(
            transform = ::siteSummary
        )
    val obligations = step.obligations.joinToString { "${it.cause}@${it.site.range}" }
    return "Observed source=${siteSummary(step.source)} stepRetainedBytes=${step.retainedBytes} " +
        "terminal=${step.terminal} transfers=${step.transfers.size} reviewed=${step.reviewedTransfers.size} " +
        "targets=[$targets] obligations=[$obligations]"
}

private fun siteSummary(site: ValueSite): String =
    "${site.range} role=${site.role.javaClass.simpleName} retainedBytes=${site.retainedBytes} " +
        "reservedObligationBytes=${reservedValueObligationBytes(site)} " +
        "pathDepth=${site.valuePath.depth} conditions=${site.conditions.facts.size} frames=${site.callStack.frames.size}"

private const val DIAGNOSTIC_HISTORY_STEPS = 3
