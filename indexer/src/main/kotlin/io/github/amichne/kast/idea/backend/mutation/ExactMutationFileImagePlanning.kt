package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.edit.IdeaNormalizedTextEdit
import io.github.amichne.kast.idea.edit.IdeaTextImagePlanner
import io.github.amichne.kast.idea.edit.IdeaTextImagePlanningException
import io.github.amichne.kast.idea.edit.IdeaUtf16Offset
import io.github.amichne.kast.idea.runIdeaReadAction
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal enum class ExactMutationFileImageFailure {
    UNSAVED_DOCUMENT,
    RAW_IMAGE_UNAVAILABLE,
    TEXT_MAPPING_UNPROVEN,
}

internal class ExactMutationFileImagePlanningException(
    val failure: ExactMutationFileImageFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun KastIndexerBackend.planExactMutationFileImages(
    edits: List<TextEdit>,
): List<ExactFileImage> = edits
    .groupBy(TextEdit::filePath)
    .map { (filePath, fileEdits) ->
        val normalizedDocumentText = normalizedSavedDocumentText(filePath)
        val rawPreimage = try {
            exactFileImageMutation.readFileBytes(
                target = Path.of(filePath),
                mutation = IdeaWorkspaceMutation.TEXT_EDIT,
            )
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw ExactMutationFileImagePlanningException(
                failure = ExactMutationFileImageFailure.RAW_IMAGE_UNAVAILABLE,
                message = "The exact raw source image could not be read safely",
                cause = failure,
            )
        }
        try {
            IdeaTextImagePlanner.plan(
                rawPreimage = rawPreimage,
                normalizedDocumentText = normalizedDocumentText,
                edits = fileEdits.map { edit ->
                    IdeaNormalizedTextEdit(
                        startOffset = IdeaUtf16Offset(edit.startOffset),
                        endOffset = IdeaUtf16Offset(edit.endOffset),
                        replacementText = edit.newText,
                    )
                },
            ).exactFileImage(filePath)
        } catch (failure: IdeaTextImagePlanningException) {
            throw ExactMutationFileImagePlanningException(
                failure = ExactMutationFileImageFailure.TEXT_MAPPING_UNPROVEN,
                message = "The normalized compiler edit could not map to the exact raw source image",
                cause = failure,
            )
        }
    }

private fun KastIndexerBackend.normalizedSavedDocumentText(filePath: String): String = runIdeaReadAction {
    val file = findKtFile(filePath)
    val manager = FileDocumentManager.getInstance()
    val document = manager.getDocument(file.virtualFile)
        ?: throw ExactMutationFileImagePlanningException(
            failure = ExactMutationFileImageFailure.TEXT_MAPPING_UNPROVEN,
            message = "The normalized IntelliJ source document is unavailable",
        )
    if (manager.isDocumentUnsaved(document)) {
        throw ExactMutationFileImagePlanningException(
            failure = ExactMutationFileImageFailure.UNSAVED_DOCUMENT,
            message = "Exact mutation planning refuses an unsaved IntelliJ source document",
        )
    }
    document.text
}
