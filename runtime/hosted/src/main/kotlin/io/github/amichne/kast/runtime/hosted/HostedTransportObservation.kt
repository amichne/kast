package io.github.amichne.kast.runtime.hosted

import java.util.UUID
import kotlinx.serialization.Serializable

/** Bounded transport evidence contains no request, source, reference, or exception text. */
@Serializable
internal data class HostedTransportObservation(
    val connectionId: String,
    val stage: HostedTransportStage,
    val outcome: HostedEndpointOutcome,
    val elapsedNanos: Long,
    val bytes: Long,
    val failure: HostedEndpointFailure? = null,
)

@Serializable
internal enum class HostedTransportStage {
    ACCEPT,
    REQUEST_READ,
    SEMANTIC_ADMISSION,
    EXECUTION,
    ENCODING,
    REPLY_WRITE,
}

/** One connection owns its correlation and monotonic stage clock through retirement. */
internal class HostedTransportTrace(
    private val observer: HostedEndpointObserver,
    private val clock: () -> Long = System::nanoTime,
) {
    private val connectionId = UUID.randomUUID()
    private var stage = HostedTransportStage.ACCEPT
    private var started = clock()

    fun enter(next: HostedTransportStage) {
        stage = next
        started = clock()
        emit(HostedEndpointOutcome.STARTED)
    }

    fun emit(
        outcome: HostedEndpointOutcome,
        bytes: Long = 0,
        failure: HostedEndpointFailure? = null,
    ) {
        observer.transport(
            HostedTransportObservation(
                connectionId.toString(),
                stage,
                outcome,
                (clock() - started).coerceAtLeast(0),
                bytes,
                failure,
            )
        )
    }
}
