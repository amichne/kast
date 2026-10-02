package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Reuses indexed occurrence capture/declaration discovery; the existing exact query stage refines identities. */
internal class IntellijTextDeclarationDiscoveryQuery(
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val clock: IntellijDiscoveryNanoClock = SystemIntellijDiscoveryNanoClock,
    private val limits: ReadLimits = ReadLimits.Default,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    fun discover(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        allowance: IntellijDeclarationDiscoveryAllowance = IntellijDeclarationDiscoveryAllowance(request),
        process: ((PsiElement, Int) -> Boolean) -> Boolean,
    ): IntellijNativeDiscoveryExecution {
        val target =
            request.target as? SymbolDiscoveryTarget.TextDeclarations
                ?: return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        val collector = SupplementalCollector(request, environmentState, clock, nativeWork = true)
        if (compiledScope.population == IntellijScopePopulation.KNOWN_EMPTY) return collector.finish()
        if (allowance.expired())
            return collector.finishLimited(allowance, SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
        if (allowance.consumedWork >= request.budget.resources.workUnitLimit.value)
            return collector.finishLimited(allowance, SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
        val projector = IntellijTextDeclarationProjector(request, compiledScope, target.word, collector, observation)
        val timings = TextDiscoveryTimings(clock)
        collectTextDiscoveryOccurrences(
            request.budget.resources.workUnitLimit,
            observe = {
                if (allowance.expired()) {
                    collector.qualify(SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
                    false
                } else collector.observe()
            },
            qualify = collector::qualify,
            process = { accept -> timings.native { process(accept) } },
            project = { element, offset -> timings.projection { projector.project(element, offset) } },
            limits = limits,
            admit = projector::admit,
            observation = observation,
            consume = allowance::consume,
        )
        collector.recordNativeWork(allowance.consumedWork)
        collector.recordTextTimings(timings.nativeNanos, timings.projectionNanos)
        return collector.finish()
    }

    private fun SupplementalCollector.finishLimited(
        allowance: IntellijDeclarationDiscoveryAllowance,
        qualification: SymbolDiscoveryQualification,
    ): IntellijNativeDiscoveryExecution {
        qualify(qualification)
        recordNativeWork(allowance.consumedWork)
        return finish()
    }
}

/** Phase clocks measure failed native attempts as well as successful projections, including before cancellation. */
private class TextDiscoveryTimings(private val clock: IntellijDiscoveryNanoClock) {
    var nativeNanos = 0L
        private set

    var projectionNanos = 0L
        private set

    fun native(effect: () -> Boolean): Boolean {
        val started = clock.now()
        try {
            return effect()
        } finally {
            nativeNanos = clock.now() - started
        }
    }

    fun projection(effect: () -> Boolean): Boolean {
        val started = clock.now()
        try {
            return effect()
        } finally {
            projectionNanos += (clock.now() - started).coerceAtLeast(0L)
        }
    }
}
