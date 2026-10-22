package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultAllowance
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameRelevance
import io.github.amichne.kast.symbol.contract.relevance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** One collection grant for native index inputs and scoped declaration inputs; projection follows callbacks. */
internal class IntellijIndexedDiscoveryAdmission(
    private val collector: BoundedNativeDiscoveryCollector,
    private val request: SymbolDiscoveryRequest,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
) {
    private val contributor
        get() = collector.contributor

    private val target = request.target
    private val fuzzy = (target as? SymbolDiscoveryTarget.Name)?.takeIf { it.match == SymbolDiscoveryMatch.FUZZY }
    private val capacity: ResultAllowance =
        when (val resources = request.budget.resources) {
            is ResourceBudget.Bounded ->
                when (
                    val limit =
                        ResultLimit.parse(
                            minOf(
                                    resources.workUnitLimit.value,
                                    limits[ReadLimitParameter.DISCOVERY_CANDIDATES].value.toLong(),
                                )
                                .toInt()
                        )
                ) {
                    is Refinement.Refined -> ResultAllowance.Bounded(limit.value)
                    is Refinement.Rejected -> error("Positive discovery limits produced an invalid capacity")
                }
            ResourceBudget.RunnerControlled -> ResultAllowance.Unlimited
        }
    private val ranked =
        when (val allowance = capacity) {
            is ResultAllowance.Bounded ->
                fuzzy?.let {
                    BoundedLexicalCandidates(
                        it.pattern,
                        minOf(allowance.limit.value, request.budget.outputResults.value),
                    )
                }
            ResultAllowance.Unlimited -> null
        }
    private val pending = ArrayList<NavigationItem>()
    private val identities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NavigationItem, Boolean>())
    private var reachedLimit = false
    private var projectedCount = 0

    fun collect(
        process:
            (
                () -> Boolean,
                (SymbolDiscoveryQualification) -> Unit,
                (IntellijDiscoveryDeclarationInput) -> Boolean,
            ) -> Boolean
    ): Boolean {
        val complete = process(collector::observe, collector::qualify, ::accept)
        retainRanked()
        qualifyCompletion(complete)
        for (index in projectedCount until pending.size) if (!project(pending[index])) break
        projectedCount = pending.size
        return complete && !collector.halted && !reachedLimit
    }

    private fun project(item: NavigationItem): Boolean {
        if (!matchesExactName(item)) {
            collector.qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
            return true
        }
        return collector.accept(item)
    }

    private fun accept(input: IntellijDiscoveryDeclarationInput): Boolean {
        val item = input.item
        if (!collector.observe()) return false
        if (!matchesExactName(item) || !matchesFuzzyName(item)) return true
        if (
            collector.admit(item, input is IntellijDiscoveryDeclarationInput.Scoped) !=
                IntellijDiscoveryItemAdmission.ADMITTED
        )
            return !collector.halted
        if (ranked != null) ranked.accept(item).observe(observation, contributor)
        else {
            if (item in identities) return true
            if (!capacity.hasCapacity(pending.size)) {
                reachedLimit = true
                return false
            }
            pending += item
            identities += item
        }
        observation.count(IntellijReadCounter.CANDIDATES_COLLECTED, collector.contributor)
        return true
    }

    private fun matchesExactName(item: NavigationItem): Boolean =
        target !is SymbolDiscoveryTarget.Name ||
            target.match != SymbolDiscoveryMatch.EXACT_NAME ||
            item.name == target.pattern.value

    private fun matchesFuzzyName(item: NavigationItem): Boolean {
        val matching = fuzzy ?: return true
        observation.count(IntellijReadCounter.NAMES_VISITED, contributor)
        val name = item.name ?: return false
        if (matching.pattern.relevance(name) == SymbolNameRelevance.UNMATCHED) return false
        observation.count(IntellijReadCounter.NAMES_MATCHED, contributor)
        return true
    }

    private fun retainRanked() {
        val ranking = ranked ?: return
        pending += ranking.values()
        if (ranking.truncated)
            collector.qualify(
                if (capacity.permits(request.budget.outputResults.value))
                    SymbolDiscoveryQualification.RESULT_LIMIT_REACHED
                else SymbolDiscoveryQualification.WORK_LIMIT_REACHED
            )
    }

    private fun qualifyCompletion(complete: Boolean) {
        if (reachedLimit) {
            observation.terminated(
                if (pending.size == limits[ReadLimitParameter.DISCOVERY_CANDIDATES].value)
                    IntellijReadTermination.CANDIDATE_CAP
                else IntellijReadTermination.WORK_LIMIT,
                collector.contributor,
            )
            collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
        } else if (!complete && !collector.halted) collector.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
    }
}
