package io.github.amichne.kast.change.apply.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyPreconditionFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyRecoveryFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyUncertainFailure
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExactFileContentProofFailure
import io.github.amichne.kast.kernel.Refinement
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

internal sealed interface IntellijAddDeclarationPreparation {
    data class Ready(
        val target: KtFile,
        val declaration: KtDeclaration,
        val document: Document,
        val sourceImages: ExactIntellijSourceImages,
        val prefixWhitespace: String,
    ) : IntellijAddDeclarationPreparation

    data class Rejected(
        val failure: AddDeclarationApplyPreconditionFailure,
    ) : IntellijAddDeclarationPreparation
}

internal data class ExactAppend(
    val prefixWhitespace: String,
)

internal sealed interface IntellijCommandExecution {
    data object NotInvoked : IntellijCommandExecution
    data object CommandCompleted : IntellijCommandExecution

    data class RejectedBeforeMutation(
        val failure: AddDeclarationApplyPreconditionFailure,
    ) : IntellijCommandExecution

    data class MutationOutcomeUnknown(
        val failure: AddDeclarationApplyUncertainFailure,
    ) : IntellijCommandExecution

    data class RecoveryRequiredAfterMutation(
        val failure: AddDeclarationApplyRecoveryFailure,
    ) : IntellijCommandExecution
}

internal enum class IntellijApplyAttemptProgress {
    NOT_BEGUN,
    MAY_HAVE_BEGUN,
    BEGUN,
    COMMAND_COMPLETED,
}

internal fun commandFailure(progress: IntellijApplyAttemptProgress): IntellijCommandExecution =
    when (progress) {
        IntellijApplyAttemptProgress.NOT_BEGUN ->
            IntellijCommandExecution.RejectedBeforeMutation(
                AddDeclarationApplyPreconditionFailure.WRITE_COMMAND_NOT_ENTERED,
            )
        IntellijApplyAttemptProgress.MAY_HAVE_BEGUN ->
            IntellijCommandExecution.MutationOutcomeUnknown(
                AddDeclarationApplyUncertainFailure.WRITE_COMMAND_FAILED,
            )
        IntellijApplyAttemptProgress.BEGUN,
        IntellijApplyAttemptProgress.COMMAND_COMPLETED,
        -> IntellijCommandExecution.RecoveryRequiredAfterMutation(
            AddDeclarationApplyRecoveryFailure.WRITE_COMMAND_FAILED,
        )
    }

internal sealed interface IntellijFinalPrecondition {
    data object Ready : IntellijFinalPrecondition

    data class Rejected(
        val failure: AddDeclarationApplyPreconditionFailure,
    ) : IntellijFinalPrecondition
}

internal sealed interface IntellijRuntimeAdmission {
    data object Supported : IntellijRuntimeAdmission
    data object Unsupported : IntellijRuntimeAdmission
}

/**
 * Proof transition:
 * raw IntelliJ product/build identity to `IntellijRuntimeAdmission`.
 *
 * Supported proves exact equality with the pinned KIP-030 runtime. Unsupported is the closed
 * expected outcome; raw build primitives are extracted only at the IntelliJ application boundary.
 */
internal fun admitIntellijRuntime(
    productCode: String,
    build: String,
    supportedProductCode: String,
    supportedBuild: String,
): IntellijRuntimeAdmission = if (
    productCode == supportedProductCode && build == supportedBuild
) {
    IntellijRuntimeAdmission.Supported
} else {
    IntellijRuntimeAdmission.Unsupported
}

internal sealed interface IntellijAfterCommandObservation {
    data object SaveIncomplete : IntellijAfterCommandObservation

    data class Observed(
        val changedPaths: Set<String>,
        val undoAvailability: io.github.amichne.kast.change.contract.AddDeclarationUndoAvailability,
    ) : IntellijAfterCommandObservation
}

internal fun observeAfterCommandOnEdt(
    project: Project,
    fileDocuments: FileDocumentManager,
    document: Document,
    changedPaths: Set<String>,
): IntellijAfterCommandObservation {
    fun observe(): IntellijAfterCommandObservation {
        fileDocuments.saveDocument(document)
        if (fileDocuments.isDocumentUnsaved(document)) {
            return IntellijAfterCommandObservation.SaveIncomplete
        }
        val undo = if (UndoManager.getInstance(project).isUndoAvailable(null)) {
            io.github.amichne.kast.change.contract.AddDeclarationUndoAvailability.AVAILABLE
        } else {
            io.github.amichne.kast.change.contract.AddDeclarationUndoAvailability.UNAVAILABLE
        }
        return IntellijAfterCommandObservation.Observed(changedPaths.toSet(), undo)
    }
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) return observe()
    val result = java.util.concurrent.atomic.AtomicReference<IntellijAfterCommandObservation>()
    application.invokeAndWait { result.set(observe()) }
    return result.get()
}

internal enum class Utf8SourceImageFailure {
    INVALID_UTF8,
}

@JvmInline
internal value class NormalizedIntellijDocumentImage private constructor(val text: String) {
    companion object {
        /**
         * Proof transition:
         * `ByteArray -> Refinement<NormalizedIntellijDocumentImage, Utf8SourceImageFailure>`.
         *
         * Refined establishes strict UTF-8 decoding with an optional BOM removed and line
         * separators normalized to IntelliJ's document representation. Raw text is extracted
         * only for document comparison and PSI insertion inside this adapter.
         */
        fun parse(
            bytes: ByteArray,
        ): Refinement<NormalizedIntellijDocumentImage, Utf8SourceImageFailure> {
            val content = if (bytes.startsWith(UTF8_BOM)) {
                bytes.copyOfRange(UTF8_BOM.size, bytes.size)
            } else {
                bytes
            }
            return when (val decoded = decodeUtf8(content)) {
                is Refinement.Refined -> Refinement.Refined(
                    NormalizedIntellijDocumentImage(
                        decoded.value.replace("\r\n", "\n").replace('\r', '\n'),
                    ),
                )
                is Refinement.Rejected -> decoded
            }
        }
    }
}

internal enum class ExactIntellijSourceImagesFailure {
    PREIMAGE_BYTES_MISMATCH,
    NORMALIZED_DOCUMENT_MISMATCH,
    INVALID_UTF8,
}

internal enum class ExactPhysicalSourceImageFailure {
    MISMATCH,
}

internal class ExactPhysicalPreimage internal constructor()

internal class ExactPhysicalPostimage internal constructor()

@ConsistentCopyVisibility
internal data class ExactIntellijSourceImages private constructor(
    private val preimageBytes: ByteArray,
    private val postimageBytes: ByteArray,
    val normalizedPreimage: NormalizedIntellijDocumentImage,
    val normalizedPostimage: NormalizedIntellijDocumentImage,
) {
    /**
     * Proof transition:
     * observed physical bytes to `Refinement<ExactPhysicalPreimage,
     * ExactPhysicalSourceImageFailure>`.
     *
     * Refined proves byte equality with the approved preimage. The raw bytes are extracted only at
     * the filesystem boundary.
     */
    fun admitPreimage(
        bytes: ByteArray,
    ): Refinement<ExactPhysicalPreimage, ExactPhysicalSourceImageFailure> =
        if (bytes.contentEquals(preimageBytes)) {
            Refinement.Refined(ExactPhysicalPreimage())
        } else {
            Refinement.Rejected(ExactPhysicalSourceImageFailure.MISMATCH)
        }

    /**
     * Proof transition:
     * observed physical bytes to `Refinement<ExactPhysicalPostimage,
     * ExactPhysicalSourceImageFailure>`.
     *
     * Refined proves byte equality with the approved postimage. The raw bytes are extracted only
     * at the filesystem boundary.
     */
    fun admitPostimage(
        bytes: ByteArray,
    ): Refinement<ExactPhysicalPostimage, ExactPhysicalSourceImageFailure> =
        if (bytes.contentEquals(postimageBytes)) {
            Refinement.Refined(ExactPhysicalPostimage())
        } else {
            Refinement.Rejected(ExactPhysicalSourceImageFailure.MISMATCH)
        }

    internal fun copyPreimageBytes(): ByteArray = preimageBytes.copyOf()

    internal fun copyPostimageBytes(): ByteArray = postimageBytes.copyOf()

    companion object {
        /**
         * Proof transition:
         * exact planned file proofs plus current physical bytes and normalized IntelliJ text to
         * `Refinement<ExactIntellijSourceImages, ExactIntellijSourceImagesFailure>`.
         *
         * Refined preserves BOM and line-separator bytes separately from IntelliJ's normalized
         * document representation. The closed expected failure is
         * `ExactIntellijSourceImagesFailure`; raw bytes are extracted only at the filesystem and
         * IntelliJ document boundaries.
         */
        fun admit(
            expectedPreimage: ExactFileContentProof,
            expectedPostimage: ExactFileContentProof,
            currentPhysicalBytes: ByteArray,
            normalizedDocumentText: String,
        ): Refinement<ExactIntellijSourceImages, ExactIntellijSourceImagesFailure> {
            val plannedPreimage = expectedPreimage.copyBytes()
            if (!currentPhysicalBytes.contentEquals(plannedPreimage)) {
                return Refinement.Rejected(
                    ExactIntellijSourceImagesFailure.PREIMAGE_BYTES_MISMATCH,
                )
            }
            val normalizedPreimage = NormalizedIntellijDocumentImage.parse(plannedPreimage)
                .valueOrNull()
                ?: return Refinement.Rejected(ExactIntellijSourceImagesFailure.INVALID_UTF8)
            val plannedPostimage = expectedPostimage.copyBytes()
            val normalizedPostimage = NormalizedIntellijDocumentImage.parse(plannedPostimage)
                .valueOrNull()
                ?: return Refinement.Rejected(ExactIntellijSourceImagesFailure.INVALID_UTF8)
            if (normalizedPreimage.text != normalizedDocumentText) {
                return Refinement.Rejected(
                    ExactIntellijSourceImagesFailure.NORMALIZED_DOCUMENT_MISMATCH,
                )
            }
            return Refinement.Refined(
                ExactIntellijSourceImages(
                    preimageBytes = plannedPreimage.copyOf(),
                    postimageBytes = plannedPostimage.copyOf(),
                    normalizedPreimage = normalizedPreimage,
                    normalizedPostimage = normalizedPostimage,
                ),
            )
        }
    }
}

/**
 * Proof transition: `ByteArray -> Refinement<String, Utf8SourceImageFailure>`.
 *
 * Refined establishes a strictly decoded UTF-8 source image. The closed expected failure is
 * `Utf8SourceImageFailure`; raw exact bytes are extracted only by the IntelliJ adapter.
 */
internal fun decodeUtf8(bytes: ByteArray): Refinement<String, Utf8SourceImageFailure> = try {
    Refinement.Refined(
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString(),
    )
} catch (_: Exception) {
    Refinement.Rejected(Utf8SourceImageFailure.INVALID_UTF8)
}

internal enum class ExactAppendFailure {
    NOT_EXACT_PLANNED_APPEND,
}

/**
 * Proof transition:
 * preimage, postimage, and planned declaration text to
 * `Refinement<ExactAppend, ExactAppendFailure>`.
 *
 * Refined proves the postimage is exactly the planner's file-bottom separator, declaration, and
 * final line feed appended to the preimage. The closed expected failure is `ExactAppendFailure`;
 * raw strings remain inside the IntelliJ adapter.
 */
internal fun exactAppend(
    preimage: NormalizedIntellijDocumentImage,
    postimage: NormalizedIntellijDocumentImage,
    declaration: String,
): Refinement<ExactAppend, ExactAppendFailure> {
    val preimageText = preimage.text
    val postimageText = postimage.text
    if (!postimageText.startsWith(preimageText) || !postimageText.endsWith('\n')) {
        return Refinement.Rejected(ExactAppendFailure.NOT_EXACT_PLANNED_APPEND)
    }
    val appendWithoutFinalLineFeed = postimageText.removePrefix(preimageText).dropLast(1)
    if (!appendWithoutFinalLineFeed.endsWith(declaration)) {
        return Refinement.Rejected(ExactAppendFailure.NOT_EXACT_PLANNED_APPEND)
    }
    val prefix = appendWithoutFinalLineFeed.removeSuffix(declaration)
    val expectedPrefix = when {
        preimageText.isEmpty() || preimageText.endsWith("\n\n") -> ""
        preimageText.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    return if (prefix == expectedPrefix) {
        Refinement.Refined(ExactAppend(prefix))
    } else {
        Refinement.Rejected(ExactAppendFailure.NOT_EXACT_PLANNED_APPEND)
    }
}

/**
 * Proof transition:
 * `ByteArray -> Refinement<ExactFileContentProof, ExactFileContentProofFailure>`.
 *
 * Establishes canonical SHA-256 and Base64 evidence for exact observed bytes. The closed expected
 * failure is `ExactFileContentProofFailure`; raw bytes are permitted only at this observation
 * boundary.
 */
internal fun exactImage(
    bytes: ByteArray,
): Refinement<ExactFileContentProof, ExactFileContentProofFailure> {
    val sha256 = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return ExactFileContentProof.admit(
        sha256 = sha256,
        contentBase64 = Base64.getEncoder().encodeToString(bytes),
    )
}

private val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
