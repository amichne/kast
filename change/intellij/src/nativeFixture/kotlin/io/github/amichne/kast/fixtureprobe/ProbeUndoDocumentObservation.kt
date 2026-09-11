package io.github.amichne.kast.fixtureprobe

import com.intellij.idea.ActionsBundle
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun logProbeUndoDocumentObservation(observation: ProbeUndoDocumentObservation) {
    val encoded = Json.encodeToString(ProbeUndoDocumentObservation.serializer(), observation)
    Logger.getInstance(NativeFixtureProbeExecution::class.java).info("kast_native_undo_document_observation $encoded")
}

internal fun validateProbeUndo(project: Project, editor: FileEditor): ProbeResult<Unit> {
    val manager = UndoManager.getInstance(project)
    return when {
        !manager.isUndoAvailable(editor) -> ProbeResult.Rejected(ProbeFailure.UNDO_UNAVAILABLE)
        probeUndoState(project, editor) != ProbeUndoState.PRODUCTION_CHANGE ->
            ProbeResult.Rejected(ProbeFailure.UNDO_COMMAND_MISMATCH)
        manager.isNextUndoAskConfirmation(editor) -> ProbeResult.Rejected(ProbeFailure.UNDO_CONFIRMATION_REQUIRED)
        else -> ProbeResult.Accepted(Unit)
    }
}

internal fun probeUndoState(project: Project, editor: FileEditor): ProbeUndoState {
    val manager = UndoManager.getInstance(project)
    if (!manager.isUndoAvailable(editor)) return ProbeUndoState.UNAVAILABLE
    val label = manager.getUndoActionNameAndDescription(editor)
    return if (
        label.first == ActionsBundle.message("action.undo.text", PRODUCTION_COMMAND).trim() &&
            label.second == ActionsBundle.message("action.undo.description", PRODUCTION_COMMAND).trim()
    )
        ProbeUndoState.PRODUCTION_CHANGE
    else ProbeUndoState.OTHER
}

@Serializable
internal sealed interface ProbeUndoDocumentObservation {
    val expectedDocumentSha256: String
    val observedDocumentSha256: String

    @Serializable
    @SerialName("matched")
    data class Matched(
        override val expectedDocumentSha256: String,
        override val observedDocumentSha256: String,
    ) : ProbeUndoDocumentObservation

    @Serializable
    @SerialName("mismatched")
    data class Mismatched(
        override val expectedDocumentSha256: String,
        override val observedDocumentSha256: String,
    ) : ProbeUndoDocumentObservation
}

internal fun verifyProbeUndoDocument(
    expected: ProbeDigest,
    observed: ProbeDigest,
    record: (ProbeUndoDocumentObservation) -> Unit,
): ProbeResult<Unit> =
    if (expected == observed) {
        record(ProbeUndoDocumentObservation.Matched(expected.value, observed.value))
        ProbeResult.Accepted(Unit)
    } else {
        record(ProbeUndoDocumentObservation.Mismatched(expected.value, observed.value))
        ProbeResult.Rejected(ProbeFailure.UNDO_IMAGE_MISMATCH)
    }
