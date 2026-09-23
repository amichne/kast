package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class NativeReconnectStage {
    BROKER_REPLACEMENT,
    IDE_RESTART,
    SESSION_RECONNECT,
    RETENTION,
    ATTACH,
    INITIALIZE,
    THREAD_START,
    POST_RECONNECT_SEARCH,
    POST_RECONNECT_PLAN,
    POST_RECONNECT_APPLY,
    POST_RECONNECT_REPLACEMENT,
    POST_RECONNECT_REATTACH,
    POST_RECONNECT_RETRY,
    POST_RECONNECT_RETENTION,
}

@Serializable
internal enum class NativeReconnectOutcome {
    STARTED,
    COMPLETE,
    REJECTED,
}

@Serializable
internal data class NativeReconnectObservation(
    val stage: NativeReconnectStage,
    val outcome: NativeReconnectOutcome,
    val event: String = "kast_native_reconnect_stage",
) {
    fun encode(): String = reconnectJson.encodeToString(serializer(), this)
}

private val reconnectJson = Json { encodeDefaults = true }

/** The native effect boundary reports only closed stages and outcomes, never paths or payloads. */
internal suspend fun <T> observeReconnect(
    stage: NativeReconnectStage,
    observe: (NativeReconnectObservation) -> Unit = ::reportReconnect,
    action: suspend () -> T,
): T {
    observe(NativeReconnectObservation(stage, NativeReconnectOutcome.STARTED))
    return try {
        action().also { observe(NativeReconnectObservation(stage, NativeReconnectOutcome.COMPLETE)) }
    } catch (failure: Exception) {
        observe(NativeReconnectObservation(stage, NativeReconnectOutcome.REJECTED))
        throw failure
    }
}

internal fun reportReconnect(observation: NativeReconnectObservation) {
    println(observation.encode())
    System.out.flush()
}
