package io.github.amichne.kast.change.intellij

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.change.apply.AppliedSourceWriteFailure
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded evidence at the physical postimage and exact write-set refinement boundary. */
@Serializable
internal sealed interface LiveWriteObservation {
    @Serializable @SerialName("applied") data object Applied : LiveWriteObservation

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: AppliedSourceWriteFailure) : LiveWriteObservation

    fun encode(): String = "kast_live_write_observation " + Json.encodeToString(this)
}

internal fun <Value> observeLiveWriteResult(
    result: Refinement<Value, AppliedSourceWriteFailure>,
    emit: (LiveWriteObservation) -> Unit,
): Refinement<Value, AppliedSourceWriteFailure> {
    emit(
        when (result) {
            is Refinement.Refined -> LiveWriteObservation.Applied
            is Refinement.Rejected -> LiveWriteObservation.Rejected(result.failure)
        }
    )
    return result
}

internal fun logLiveWriteObservation(evidence: LiveWriteObservation) {
    Logger.getInstance(HostedLiveSourceWriter::class.java).info(evidence.encode())
}
