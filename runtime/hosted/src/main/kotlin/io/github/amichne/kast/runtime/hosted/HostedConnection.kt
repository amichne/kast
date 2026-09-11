package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
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
    limits: ReadLimits = ReadLimits.Default,
    dispatch: suspend (HostedRequest) -> String,
) {
    observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.STARTED)
    try {
        withTimeout(limits[ReadLimitParameter.HOST_CONNECTION_MILLIS].value.toLong()) {
            val request = when (val frame = runInterruptible(Dispatchers.IO) { HostedFrames.read(input, limits) }) {
                is Refinement.Rejected -> frame
                is Refinement.Refined -> HostedRequests.decode(frame.value)
            }
            val response = when (request) {
                is Refinement.Rejected -> HostedRequests.rejected(request.failure)
                is Refinement.Refined -> dispatch(request.value)
            }
            when (val written = runInterruptible(Dispatchers.IO) { HostedFrames.write(output, response, limits) }) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> {
                    observer.rejected(HostedEndpointStage.REQUEST, written.failure)
                    runInterruptible(Dispatchers.IO) { HostedFrames.write(output, HostedRequests.rejected(written.failure), limits) }
                    return@withTimeout
                }
            }
            when (request) {
                is Refinement.Refined -> observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.COMPLETED)
                is Refinement.Rejected -> observer.rejected(HostedEndpointStage.REQUEST, request.failure)
            }
        }
    } catch (_: TimeoutCancellationException) {
        observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.DEADLINE_EXCEEDED)
    } catch (cancelled: CancellationException) {
        observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.CANCELLED)
        throw cancelled
    } catch (_: java.io.IOException) {
        observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.IO_UNAVAILABLE)
    } catch (_: RuntimeException) {
        observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.PLATFORM_UNAVAILABLE)
    }
}
