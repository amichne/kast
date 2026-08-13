package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.NativeRelationBatch
import io.github.amichne.kast.symbol.contract.NativeRelationByteCount
import io.github.amichne.kast.symbol.contract.NativeRelationElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.NativeRelationFact
import io.github.amichne.kast.symbol.contract.NativeRelationLimitation
import io.github.amichne.kast.symbol.contract.NativeRelationOutcome
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.NativeRelationTimings
import io.github.amichne.kast.symbol.contract.NativeRelationWorkCount
import java.util.concurrent.CancellationException

internal interface IntellijNativeRelationEvent

internal enum class IntellijRelationProjectionFailure {
    UNRESOLVED_TARGET,
    UNSUPPORTED_ITEM,
}

internal fun interface IntellijRelationFactProjector {
    /**
     * Proof transition:
     * NativeRelationRequest + request-local native event to
     * Refinement<NativeRelationFact, IntellijRelationProjectionFailure>.
     *
     * Establishes a detached exact endpoint and occurrence bound to the request selector, or one
     * closed unsupported/unresolved state. Live IntelliJ values remain inside this call.
     */
    fun project(
        request: NativeRelationRequest,
        event: IntellijNativeRelationEvent,
    ): Refinement<NativeRelationFact, IntellijRelationProjectionFailure>
}

internal enum class IntellijNativeRelationSearchRejection {
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    SELECTOR_CHANGED,
}

internal sealed interface IntellijNativeRelationSearchResult {
    data class Terminal(
        val limitations: Set<NativeRelationLimitation> = emptySet(),
    ) : IntellijNativeRelationSearchResult

    data class Halted(
        val limitations: Set<NativeRelationLimitation> = emptySet(),
    ) : IntellijNativeRelationSearchResult

    data class Rejected(
        val reason: IntellijNativeRelationSearchRejection,
    ) : IntellijNativeRelationSearchResult
}

internal fun interface IntellijNativeRelationSearch {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + NativeRelationRequest + bounded event consumer to
     * IntellijNativeRelationSearchResult.
     *
     * Establishes that the exact selector was revalidated and its requested one-hop family was
     * streamed only through the compiled native scope. Terminal completion is explicit; subject
     * identity failures and incomplete provider coverage are closed states. Live PSI and query
     * objects remain inside this request-local call.
     */
    fun search(
        compiledScope: CompiledIntellijSearchScope,
        request: NativeRelationRequest,
        consumer: (IntellijNativeRelationEvent) -> Boolean,
    ): IntellijNativeRelationSearchResult
}

internal enum class IntellijNativeRelationRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    DUMB_MODE,
    PROJECT_DISPOSED,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    SELECTOR_CHANGED,
    INTERNAL_INVARIANT,
}

internal sealed interface IntellijNativeRelationExecution {
    data class Produced(
        val outcome: NativeRelationOutcome,
    ) : IntellijNativeRelationExecution

    data class Rejected(
        val reason: IntellijNativeRelationRejection,
    ) : IntellijNativeRelationExecution
}

internal fun interface IntellijRelationNanoClock {
    fun now(): Long
}

internal object SystemIntellijRelationNanoClock : IntellijRelationNanoClock {
    override fun now(): Long = System.nanoTime()
}

internal class IntellijNativeRelationQuery(
    private val search: IntellijNativeRelationSearch,
    private val projector: IntellijRelationFactProjector,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijRelationNanoClock = SystemIntellijRelationNanoClock,
) {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + NativeRelationRequest + native one-hop providers to
     * IntellijNativeRelationExecution.
     *
     * Establishes exact selector/scope agreement, request-local revalidation, cancellation checks
     * inside every event, projection after record/work/time admission, byte bounds, deterministic
     * facts, and exact cardinality only after terminal enumeration. Expected rejection and partial
     * coverage states are [IntellijNativeRelationRejection] and [NativeRelationLimitation].
     * Platform cancellation propagates.
     */
    fun read(
        compiledScope: CompiledIntellijSearchScope,
        request: NativeRelationRequest,
    ): IntellijNativeRelationExecution {
        if (
            compiledScope.lease != request.selector.lease ||
            compiledScope.scope != request.selector.scope
        ) {
            return rejected(IntellijNativeRelationRejection.INTERNAL_INVARIANT)
        }
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB ->
                return rejected(IntellijNativeRelationRejection.DUMB_MODE)
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                return rejected(IntellijNativeRelationRejection.PROJECT_DISPOSED)
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        cancellationCheck()

        val collector = BoundedIntellijRelationCollector(
            request = request,
            projector = projector,
            environmentState = environmentState,
            cancellationCheck = cancellationCheck,
            clock = clock,
        )
        val searchResult = try {
            search.search(compiledScope, request, collector::accept)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            collector.qualifyAndHalt(NativeRelationLimitation.DUMB_MODE_TRANSITION)
            IntellijNativeRelationSearchResult.Halted()
        } catch (_: RuntimeException) {
            collector.qualifyAndHalt(NativeRelationLimitation.PROVIDER_FAILURE)
            IntellijNativeRelationSearchResult.Halted()
        }
        return when (searchResult) {
            is IntellijNativeRelationSearchResult.Rejected ->
                rejected(searchResult.reason.publicRejection())
            is IntellijNativeRelationSearchResult.Terminal -> {
                searchResult.limitations.forEach(collector::qualify)
                collector.finish()
            }
            is IntellijNativeRelationSearchResult.Halted -> {
                searchResult.limitations.forEach(collector::qualify)
                if (collector.state == IntellijRelationCollectorState.COLLECTING) {
                    collector.qualify(NativeRelationLimitation.PROVIDER_INCOMPLETE)
                }
                collector.finish()
            }
        }
    }
}

private enum class IntellijRelationCollectorState {
    COLLECTING,
    HALTED,
}

private class BoundedIntellijRelationCollector(
    private val request: NativeRelationRequest,
    private val projector: IntellijRelationFactProjector,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijRelationNanoClock,
) {
    private enum class EventAdmission {
        ADMITTED,
        HALTED,
    }

    private val startedAt = clock.now()
    private val facts = linkedSetOf<NativeRelationFact>()
    private val limitations = linkedSetOf<NativeRelationLimitation>()
    private var encodedBytes = 0L
    private var workUnits = 0L
    private var projectionNanoseconds = 0L
    var state = IntellijRelationCollectorState.COLLECTING
        private set

    fun accept(event: IntellijNativeRelationEvent): Boolean {
        if (state == IntellijRelationCollectorState.HALTED) {
            return false
        }
        if (admitEvent() == EventAdmission.HALTED) {
            return false
        }
        val projectionStartedAt = clock.now()
        val fact = when (val projected = projector.project(request, event)) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> {
                projectionNanoseconds = saturatedAdd(
                    projectionNanoseconds,
                    elapsedSince(projectionStartedAt),
                )
                qualify(projected.failure.limitation())
                return true
            }
        }
        projectionNanoseconds = saturatedAdd(
            projectionNanoseconds,
            elapsedSince(projectionStartedAt),
        )
        if (fact in facts) {
            return true
        }
        val factBytes = fact.projectedUtf8Size()
        if (factBytes.value > request.budget.returnedBytes.value - encodedBytes) {
            qualifyAndHalt(NativeRelationLimitation.BYTE_LIMIT_REACHED)
            return false
        }
        facts += fact
        encodedBytes += factBytes.value
        return true
    }

    fun qualify(limitation: NativeRelationLimitation) {
        limitations += limitation
    }

    fun qualifyAndHalt(limitation: NativeRelationLimitation) {
        qualify(limitation)
        state = IntellijRelationCollectorState.HALTED
    }

    fun finish(): IntellijNativeRelationExecution {
        if (elapsedSince(startedAt) >= request.elapsedLimitNanoseconds()) {
            qualify(NativeRelationLimitation.TIME_LIMIT_REACHED)
        }
        val totalNanoseconds = elapsedSince(startedAt)
        val batch = when (
            val created = NativeRelationBatch.create(
                request = request,
                facts = facts.sorted(),
                encodedBytes = encodedBytes.byteMeasure(),
                examinedWorkUnits = workUnits.workMeasure(),
                timings = NativeRelationTimings(
                    nativeQuery = (totalNanoseconds - projectionNanoseconds)
                        .coerceAtLeast(0L)
                        .elapsedMeasure(),
                    projection = projectionNanoseconds.elapsedMeasure(),
                ),
            )
        ) {
            is Refinement.Refined -> created.value
            is Refinement.Rejected ->
                return rejected(IntellijNativeRelationRejection.INTERNAL_INVARIANT)
        }
        val outcome = if (limitations.isEmpty()) {
            NativeRelationOutcome.complete(batch)
        } else {
            when (val qualified = NativeRelationOutcome.qualified(batch, limitations)) {
                is Refinement.Refined -> qualified.value
                is Refinement.Rejected ->
                    return rejected(IntellijNativeRelationRejection.INTERNAL_INVARIANT)
            }
        }
        return IntellijNativeRelationExecution.Produced(outcome)
    }

    private fun admitEvent(): EventAdmission {
        cancellationCheck()
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB -> {
                qualifyAndHalt(NativeRelationLimitation.DUMB_MODE_TRANSITION)
                return EventAdmission.HALTED
            }
            IntellijDiscoveryEnvironmentState.DISPOSED -> {
                qualifyAndHalt(NativeRelationLimitation.PROVIDER_FAILURE)
                return EventAdmission.HALTED
            }
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        if (elapsedSince(startedAt) >= request.elapsedLimitNanoseconds()) {
            qualifyAndHalt(NativeRelationLimitation.TIME_LIMIT_REACHED)
            return EventAdmission.HALTED
        }
        if (workUnits >= request.budget.resources.workUnitLimit.value) {
            qualifyAndHalt(NativeRelationLimitation.WORK_LIMIT_REACHED)
            return EventAdmission.HALTED
        }
        workUnits += 1L
        if (facts.size >= request.budget.resources.resultLimit.value) {
            qualifyAndHalt(NativeRelationLimitation.RESULT_LIMIT_REACHED)
            return EventAdmission.HALTED
        }
        return EventAdmission.ADMITTED
    }

    private fun elapsedSince(start: Long): Long = (clock.now() - start).coerceAtLeast(0L)
}

private fun IntellijRelationProjectionFailure.limitation(): NativeRelationLimitation = when (this) {
    IntellijRelationProjectionFailure.UNRESOLVED_TARGET ->
        NativeRelationLimitation.UNRESOLVED_TARGET
    IntellijRelationProjectionFailure.UNSUPPORTED_ITEM ->
        NativeRelationLimitation.UNSUPPORTED_ITEM
}

private fun IntellijNativeRelationSearchRejection.publicRejection():
    IntellijNativeRelationRejection = when (this) {
    IntellijNativeRelationSearchRejection.STALE_SELECTOR ->
        IntellijNativeRelationRejection.STALE_SELECTOR
    IntellijNativeRelationSearchRejection.OUTSIDE_SCOPE ->
        IntellijNativeRelationRejection.OUTSIDE_SCOPE
    IntellijNativeRelationSearchRejection.AMBIGUOUS_SUBJECT ->
        IntellijNativeRelationRejection.AMBIGUOUS_SUBJECT
    IntellijNativeRelationSearchRejection.UNSUPPORTED_SUBJECT ->
        IntellijNativeRelationRejection.UNSUPPORTED_SUBJECT
    IntellijNativeRelationSearchRejection.SELECTOR_CHANGED ->
        IntellijNativeRelationRejection.SELECTOR_CHANGED
}

private fun rejected(
    reason: IntellijNativeRelationRejection,
): IntellijNativeRelationExecution.Rejected = IntellijNativeRelationExecution.Rejected(reason)

private fun NativeRelationRequest.elapsedLimitNanoseconds(): Long {
    val millis = budget.resources.elapsedTimeLimit.value
    return if (millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
        Long.MAX_VALUE
    } else {
        millis * NANOS_PER_MILLISECOND
    }
}

private fun Long.byteMeasure(): NativeRelationByteCount =
    (NativeRelationByteCount.parse(this) as Refinement.Refined).value

private fun Long.workMeasure(): NativeRelationWorkCount =
    (NativeRelationWorkCount.parse(this) as Refinement.Refined).value

private fun Long.elapsedMeasure(): NativeRelationElapsedNanoseconds =
    (NativeRelationElapsedNanoseconds.parse(this) as Refinement.Refined).value

private fun saturatedAdd(
    left: Long,
    right: Long,
): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private const val NANOS_PER_MILLISECOND = 1_000_000L
