package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.ObserverPresentation

internal enum class PendingObserverPresentationWrite {
    STORED,
    DISCARDED_CAPACITY,
    DISCARDED_DUPLICATE,
}

internal sealed interface PendingObserverPresentationTake {
    data class Found(val presentation: ObserverPresentation.Available) : PendingObserverPresentationTake

    data object Missing : PendingObserverPresentationTake
}

/**
 * Connection-local, bounded, non-persistent call correlation. Completions can arrive out of order, so this is an atomic
 * keyed take rather than an ordered asynchronous stream.
 */
internal class PendingObserverPresentations private constructor(private val capacity: Int) {
    private val presentations = linkedMapOf<BrokerCallId, ObserverPresentation.Available>()

    internal fun put(
        callId: BrokerCallId,
        presentation: ObserverPresentation.Available,
    ): PendingObserverPresentationWrite =
        synchronized(presentations) {
            when {
                presentations.containsKey(callId) -> PendingObserverPresentationWrite.DISCARDED_DUPLICATE
                presentations.size >= capacity -> PendingObserverPresentationWrite.DISCARDED_CAPACITY
                else -> {
                    presentations[callId] = presentation
                    PendingObserverPresentationWrite.STORED
                }
            }
        }

    internal fun take(callId: BrokerCallId): PendingObserverPresentationTake =
        synchronized(presentations) {
            presentations.remove(callId)?.let(PendingObserverPresentationTake::Found)
                ?: PendingObserverPresentationTake.Missing
        }

    internal fun clear() {
        synchronized(presentations) { presentations.clear() }
    }

    companion object {
        internal fun withCapacity(capacity: Int): PendingObserverPresentations {
            require(capacity > 0) { "Pending observer capacity must be positive" }
            return PendingObserverPresentations(capacity)
        }
    }
}
