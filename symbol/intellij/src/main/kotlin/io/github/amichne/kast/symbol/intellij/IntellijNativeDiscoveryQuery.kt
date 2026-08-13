package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.text.matching.MatchingMode
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import java.util.concurrent.CancellationException

internal enum class IntellijDiscoveryEnvironmentState {
    READY,
    DUMB,
    DISPOSED,
}

internal enum class IntellijNativeDiscoveryRejection {
    DUMB_MODE,
    PROJECT_DISPOSED,
    NO_NATIVE_PROVIDERS,
    INTERNAL_INVARIANT,
}

internal sealed interface IntellijNativeDiscoveryExecution {
    data class Produced(
        val outcome: SymbolDiscoveryOutcome,
    ) : IntellijNativeDiscoveryExecution

    data class Rejected(
        val reason: IntellijNativeDiscoveryRejection,
    ) : IntellijNativeDiscoveryExecution
}

internal fun interface IntellijDiscoveryNanoClock {
    fun now(): Long
}

internal object SystemIntellijDiscoveryNanoClock : IntellijDiscoveryNanoClock {
    override fun now(): Long = System.nanoTime()
}

internal class IntellijNativeDiscoveryQuery(
    private val itemFile: IntellijDiscoveryItemFile = IntellijPsiDiscoveryItemFile,
    private val projector: IntellijDiscoveryCandidateProjector =
        IntellijPsiDiscoveryCandidateProjector,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijDiscoveryNanoClock = SystemIntellijDiscoveryNanoClock,
) {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + SymbolDiscoveryRequest + native contributors to
     * IntellijNativeDiscoveryExecution.
     *
     * Establishes that each provider receives the compiled scope before native index work, each
     * item is scope-checked before PSI projection, and every returned candidate is detached,
     * deterministic, record/byte/work/time bounded, and generation-bound.
     * [IntellijNativeDiscoveryRejection] and [SymbolDiscoveryQualification] are the closed expected
     * failure and partial-coverage states. Cancellation remains a platform cancellation and is
     * propagated. Live contributors, navigation items, virtual files, and scopes remain inside this
     * request-local call.
     */
    fun discover(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        contributors: List<ChooseByNameContributor>,
    ): IntellijNativeDiscoveryExecution {
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB ->
                return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.DUMB_MODE,
                )
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.PROJECT_DISPOSED,
                )
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        if (contributors.isEmpty()) {
            return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.NO_NATIVE_PROVIDERS,
            )
        }

        val collector = BoundedNativeDiscoveryCollector(
            compiledScope = compiledScope,
            request = request,
            itemFile = itemFile,
            projector = projector,
            environmentState = environmentState,
            cancellationCheck = cancellationCheck,
            clock = clock,
        )
        val matcher = NameUtil.buildMatcher(
            "*${request.pattern.value}",
            MatchingMode.IGNORE_CASE,
        )
        val parameters = FindSymbolParameters.wrap(
            request.pattern.value,
            compiledScope.nativeScope,
        )

        contributors.sortedBy { it.javaClass.name }.forEach { contributor ->
            if (collector.halted) {
                return@forEach
            }
            if (contributor !is ChooseByNameContributorEx) {
                collector.qualify(SymbolDiscoveryQualification.UNSCOPED_PROVIDER)
                return@forEach
            }
            try {
                contributor.processNames(
                    Processor { name ->
                        if (!collector.admitWork()) {
                            return@Processor false
                        }
                        if (!matcher.matches(name)) {
                            return@Processor true
                        }
                        contributor.processElementsWithName(
                            name,
                            Processor { item -> collector.accept(item) },
                            parameters,
                        )
                        !collector.halted
                    },
                    compiledScope.nativeScope,
                    null,
                )
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IndexNotReadyException) {
                collector.qualifyAndHalt(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION)
            } catch (_: RuntimeException) {
                collector.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
            }
        }
        return collector.finish()
    }
}

private class BoundedNativeDiscoveryCollector(
    private val compiledScope: CompiledIntellijSearchScope,
    private val request: SymbolDiscoveryRequest,
    private val itemFile: IntellijDiscoveryItemFile,
    private val projector: IntellijDiscoveryCandidateProjector,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijDiscoveryNanoClock,
) {
    private val startedAt = clock.now()
    private val candidates = linkedSetOf<SymbolDiscoveryCandidate>()
    private val qualifications = linkedSetOf<SymbolDiscoveryQualification>()
    private var encodedBytes = 0L
    private var workUnits = 0L
    private var projectionNanoseconds = 0L
    var halted: Boolean = false
        private set

    fun admitWork(): Boolean {
        cancellationCheck()
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB -> {
                qualifyAndHalt(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION)
                return false
            }
            IntellijDiscoveryEnvironmentState.DISPOSED -> {
                qualifyAndHalt(SymbolDiscoveryQualification.PROVIDER_FAILURE)
                return false
            }
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        if (elapsedSince(startedAt) >= request.elapsedLimitNanoseconds()) {
            qualifyAndHalt(SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
            return false
        }
        if (workUnits >= request.budget.resources.workUnitLimit.value) {
            qualifyAndHalt(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
            return false
        }
        workUnits += 1L
        return true
    }

    fun accept(item: NavigationItem): Boolean {
        if (!admitWork()) {
            return false
        }
        val file = when (val itemFileResult = itemFile.find(item)) {
            is IntellijDiscoveryItemFileResult.Found -> itemFileResult.file
            IntellijDiscoveryItemFileResult.Unsupported -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        if (!compiledScope.nativeScope.contains(file)) {
            return true
        }
        if (candidates.size >= request.budget.resources.resultLimit.value) {
            qualifyAndHalt(SymbolDiscoveryQualification.RESULT_LIMIT_REACHED)
            return false
        }

        val projectionStartedAt = clock.now()
        val projected = projector.project(request, item, file)
        projectionNanoseconds = saturatedAdd(
            projectionNanoseconds,
            elapsedSince(projectionStartedAt),
        )
        val candidate = when (projected) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        if (candidate in candidates) {
            return true
        }
        val candidateBytes = candidate.projectedUtf8Size()
        if (candidateBytes.value > request.budget.returnedBytes.value - encodedBytes) {
            qualifyAndHalt(SymbolDiscoveryQualification.BYTE_LIMIT_REACHED)
            return false
        }
        candidates += candidate
        encodedBytes += candidateBytes.value
        return true
    }

    fun qualify(qualification: SymbolDiscoveryQualification) {
        qualifications += qualification
    }

    fun qualifyAndHalt(qualification: SymbolDiscoveryQualification) {
        qualify(qualification)
        halted = true
    }

    fun finish(): IntellijNativeDiscoveryExecution {
        if (elapsedSince(startedAt) >= request.elapsedLimitNanoseconds()) {
            qualify(SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
        }
        val totalNanoseconds = elapsedSince(startedAt)
        val timings = SymbolDiscoveryTimings(
            nativeQuery = (totalNanoseconds - projectionNanoseconds)
                .coerceAtLeast(0L)
                .elapsedMeasure(),
            projection = projectionNanoseconds.elapsedMeasure(),
        )
        val orderedCandidates = candidates.sorted()
        val batch = when (
            val creation = SymbolDiscoveryBatch.create(
                request = request,
                candidates = orderedCandidates,
                encodedBytes = encodedBytes.byteMeasure(),
                examinedWorkUnits = workUnits.workMeasure(),
                timings = timings,
            )
        ) {
            is Refinement.Refined -> creation.value
            is Refinement.Rejected ->
                return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
                )
        }
        val outcome = if (qualifications.isEmpty()) {
            SymbolDiscoveryOutcome.Complete(batch)
        } else {
            val typedQualifications = when (
                val refinement = SymbolDiscoveryQualifications.from(qualifications)
            ) {
                is Refinement.Refined -> refinement.value
                is Refinement.Rejected ->
                    return IntellijNativeDiscoveryExecution.Rejected(
                        IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
                    )
            }
            SymbolDiscoveryOutcome.Qualified(batch, typedQualifications)
        }
        return IntellijNativeDiscoveryExecution.Produced(outcome)
    }

    private fun elapsedSince(start: Long): Long = (clock.now() - start).coerceAtLeast(0L)
}

private fun SymbolDiscoveryRequest.elapsedLimitNanoseconds(): Long {
    val millis = budget.resources.elapsedTimeLimit.value
    return if (millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
        Long.MAX_VALUE
    } else {
        millis * NANOS_PER_MILLISECOND
    }
}

private fun Long.byteMeasure(): SymbolDiscoveryByteCount =
    when (val parsed = SymbolDiscoveryByteCount.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("non-negative byte accumulator rejected")
    }

private fun Long.workMeasure(): SymbolDiscoveryWorkCount =
    when (val parsed = SymbolDiscoveryWorkCount.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("non-negative work accumulator rejected")
    }

private fun Long.elapsedMeasure(): SymbolDiscoveryElapsedNanoseconds =
    when (val parsed = SymbolDiscoveryElapsedNanoseconds.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("non-negative elapsed accumulator rejected")
    }

private fun saturatedAdd(
    left: Long,
    right: Long,
): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private const val NANOS_PER_MILLISECOND = 1_000_000L
