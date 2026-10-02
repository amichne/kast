package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import java.util.concurrent.CancellationException

private data class PendingTextOccurrence(val element: PsiElement, val offsetInElement: Int)

/** Native callbacks capture bounded references only; PSI projection follows complete collection. */
internal fun collectTextDiscoveryOccurrences(
    workLimit: WorkUnitLimit,
    observe: () -> Boolean,
    qualify: (SymbolDiscoveryQualification) -> Unit,
    process: ((PsiElement, Int) -> Boolean) -> Boolean,
    project: (PsiElement, Int) -> Boolean,
    limits: ReadLimits = ReadLimits.Default,
    admit: (PsiElement, Int) -> IntellijTextOccurrenceAdmission = { _, _ -> IntellijTextOccurrenceAdmission.ADMITTED },
    observation: IntellijReadObservation = IntellijReadObservation.None,
    consume: () -> Boolean = { true },
): Int {
    val capture =
        TextOccurrenceCapture(
            nativeLimit = minOf(workLimit.value, limits[ReadLimitParameter.DISCOVERY_CANDIDATES].value.toLong()),
            observe = observe,
            qualify = qualify,
            admit = admit,
            observation = observation,
            consume = consume,
        )
    val effects = IntellijTextDiscoveryEffectBoundary(observation, limits)
    when (val outcome = effects.execute { process(capture::accept) }) {
        IntellijTextEffectOutcome.Complete -> Unit
        IntellijTextEffectOutcome.Stopped ->
            if (!capture.stopped) qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
        is IntellijTextEffectOutcome.Qualified -> qualify(outcome.qualification)
    }
    capture.project { occurrence ->
        observe() &&
            when (val outcome = effects.execute { project(occurrence.element, occurrence.offsetInElement) }) {
                IntellijTextEffectOutcome.Complete -> true
                IntellijTextEffectOutcome.Stopped -> false
                is IntellijTextEffectOutcome.Qualified -> {
                    qualify(outcome.qualification)
                    false
                }
            }
    }
    return capture.size
}

/** Captured PSI and all shared callback state belong to one request; projection never runs under its lock. */
private class TextOccurrenceCapture(
    private val nativeLimit: Long,
    private val observe: () -> Boolean,
    private val qualify: (SymbolDiscoveryQualification) -> Unit,
    private val admit: (PsiElement, Int) -> IntellijTextOccurrenceAdmission,
    private val observation: IntellijReadObservation,
    private val consume: () -> Boolean,
) {
    private val pending = ArrayList<PendingTextOccurrence>()
    var stopped = false
        private set

    val size: Int
        get() = pending.size

    fun accept(element: PsiElement, offset: Int): Boolean {
        // Scope/kind inspection remains outside the lock. The synchronous provider drains callbacks before projection.
        val admission = admit(element, offset)
        return synchronized(pending) { acceptObserved(element, offset, admission) }
    }

    private fun acceptObserved(element: PsiElement, offset: Int, admission: IntellijTextOccurrenceAdmission): Boolean {
        if (stopped || !observe()) {
            stopped = true
            return false
        }
        return when (admission) {
            IntellijTextOccurrenceAdmission.FILTERED -> {
                observation.count(IntellijReadCounter.SCOPE_FILTERED)
                true
            }
            IntellijTextOccurrenceAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                true
            }
            IntellijTextOccurrenceAdmission.ADMITTED -> captureWithinBudget(element, offset)
        }
    }

    private fun captureWithinBudget(element: PsiElement, offset: Int): Boolean {
        if (pending.size.toLong() >= nativeLimit || !consume()) {
            stopped = true
            qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
            return false
        }
        pending += PendingTextOccurrence(element, offset)
        observation.count(IntellijReadCounter.CANDIDATES_COLLECTED)
        return true
    }

    fun project(accept: (PendingTextOccurrence) -> Boolean) {
        pending.all(accept)
    }
}

/** Maps platform index/PSI failures at the native call boundary; domain refinements remain finite data. */
private class IntellijTextDiscoveryEffectBoundary(
    private val observation: IntellijReadObservation,
    private val limits: ReadLimits,
) {
    // Platform/plugin providers have no closed unexpected exception vocabulary; retain bounded structured evidence.
    @Suppress("TooGenericExceptionCaught")
    fun execute(effect: () -> Boolean): IntellijTextEffectOutcome =
        try {
            if (effect()) IntellijTextEffectOutcome.Complete else IntellijTextEffectOutcome.Stopped
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            IntellijTextEffectOutcome.Qualified(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION)
        } catch (failure: RuntimeException) {
            observation.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.DISCOVERY, failure, limits))
            IntellijTextEffectOutcome.Qualified(SymbolDiscoveryQualification.PROVIDER_FAILURE)
        }
}

private sealed interface IntellijTextEffectOutcome {
    data object Complete : IntellijTextEffectOutcome

    data object Stopped : IntellijTextEffectOutcome

    data class Qualified(val qualification: SymbolDiscoveryQualification) : IntellijTextEffectOutcome
}

internal enum class IntellijTextOccurrenceAdmission {
    ADMITTED,
    FILTERED,
    UNSUPPORTED,
}
