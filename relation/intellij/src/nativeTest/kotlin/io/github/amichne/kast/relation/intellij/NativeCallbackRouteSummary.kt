package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueSite

private const val SUMMARY_STEPS = 8
private const val SUMMARY_DETAILS = 4

/** Bounded native frontier evidence excludes source text and recursive execution proofs. */
internal fun callbackRouteSummary(steps: List<ValueFlowStep>): String {
    val frontier = steps.filter { it.obligations.isNotEmpty() || it.transfers.isEmpty() }.take(SUMMARY_STEPS)
    val selected = (frontier + steps.takeLast(SUMMARY_STEPS)).distinct()
    return "steps=${steps.size}; " +
        selected.joinToString("; ") { step ->
            val causes = step.obligations.take(SUMMARY_DETAILS).map { it.cause }
            val targets = step.transfers.take(SUMMARY_DETAILS).map { "${it.kind}:${callbackSiteSummary(it.target)}" }
            "${callbackSiteSummary(step.source)} terminal=${step.terminal} " +
                "obligations=${step.obligations.size}:$causes transfers=${step.transfers.size}:$targets"
        }
}

private fun callbackSiteSummary(site: ValueSite): String =
    "${site.role.javaClass.simpleName}@${site.range}/owner=${site.enclosing.range}"
