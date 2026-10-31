package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.runtime.hosted.HostedEndpointFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One response owner dispatches effects only after the complete acknowledgment write succeeds. */
internal class LifecycleReply
private constructor(
    val result: IdeLifecycleResult,
    private val acknowledged: () -> Unit,
) {
    suspend fun write(
        writer: suspend (IdeLifecycleResult) -> Refinement<Unit, HostedEndpointFailure>
    ): Refinement<Unit, HostedEndpointFailure> {
        val written = writer(result)
        if (written is Refinement.Refined) acknowledged()
        return written
    }

    companion object {
        fun immediate(result: IdeLifecycleResult): LifecycleReply = LifecycleReply(result) {}

        fun afterAcknowledgment(result: IdeLifecycleResult, action: () -> Unit): LifecycleReply =
            LifecycleReply(result, action)
    }
}

@Serializable internal data class LifecycleReplyRejection(val failure: HostedEndpointFailure)

internal fun observeLifecycleReplyRejection(failure: HostedEndpointFailure) {
    Logger.getInstance(IdeLifecycleApplication::class.java)
        .info(
            "kast_lifecycle_write " +
                Json.encodeToString(LifecycleReplyRejection.serializer(), LifecycleReplyRejection(failure))
        )
}

internal fun decodeLifecycleReply(
    raw: String,
    json: Json,
    dispatch: (IdeLifecycleCommand) -> LifecycleReply,
): LifecycleReply =
    try {
        dispatch(json.decodeFromString(IdeLifecycleCommand.serializer(), raw))
    } catch (_: SerializationException) {
        LifecycleReply.immediate(IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST))
    } catch (_: IllegalArgumentException) {
        LifecycleReply.immediate(IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST))
    }
