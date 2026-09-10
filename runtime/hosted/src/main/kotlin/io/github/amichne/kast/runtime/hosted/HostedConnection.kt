package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream

/** One connection owns one bounded request, including frame I/O and detached dispatch. */
internal suspend fun serveHostedConnection(
    input: InputStream,
    output: OutputStream,
    observer: HostedEndpointObserver,
    dispatch: suspend (HostedRequest) -> String,
) {
    observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.STARTED)
    try {
        withTimeout(5_000) {
            val request = when (val frame = runInterruptible(Dispatchers.IO) { HostedFrames.read(input) }) {
                is Refinement.Rejected -> frame
                is Refinement.Refined -> HostedRequests.decode(frame.value)
            }
            val response = when (request) {
                is Refinement.Rejected -> HostedRequests.rejected(request.failure)
                is Refinement.Refined -> dispatch(request.value)
            }
            runInterruptible(Dispatchers.IO) { HostedFrames.write(output, response) }
            observer.observe(HostedEndpointStage.REQUEST, when (request) {
                is Refinement.Refined -> HostedEndpointOutcome.COMPLETED
                is Refinement.Rejected -> HostedEndpointOutcome.REJECTED
            })
        }
    } catch (_: TimeoutCancellationException) {
        observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.REJECTED)
    } catch (cancelled: CancellationException) {
        observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.CANCELLED)
        throw cancelled
    } catch (_: java.io.IOException) {
        observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.REJECTED)
    } catch (_: RuntimeException) {
        observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.REJECTED)
    }
}
