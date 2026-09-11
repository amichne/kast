package io.github.amichne.kast.change.intellij

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.change.apply.SourceWriteFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Finite evidence that physical observation crossed the platform's file-write completion boundary. */
@Serializable
internal sealed interface PhysicalWriteCompletion {
    @Serializable @SerialName("completed") data object Completed : PhysicalWriteCompletion

    @Serializable @SerialName("rejected") data class Rejected(val failure: SourceWriteFailure) : PhysicalWriteCompletion
}

internal fun observeCompletedPhysicalWrite(
    complete: () -> IntellijSessionStepResult,
    observe: () -> IntellijPhysicalSourceObservation,
    emit: (PhysicalWriteCompletion) -> Unit,
): IntellijPhysicalSourceObservation =
    when (val completed = complete()) {
        IntellijSessionStepResult.Completed -> {
            emit(PhysicalWriteCompletion.Completed)
            observe()
        }
        is IntellijSessionStepResult.Rejected -> {
            emit(PhysicalWriteCompletion.Rejected(completed.failure))
            IntellijPhysicalSourceObservation.Rejected(completed.failure)
        }
    }

internal fun logPhysicalWriteCompletion(evidence: PhysicalWriteCompletion) {
    Logger.getInstance(LiveIntellijDocumentSession::class.java)
        .info("kast_physical_write_completion " + Json.encodeToString(evidence))
}
