package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectLibrarySources
import io.github.amichne.kast.workspace.intellij.read.LibrarySourceRootFailure
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.jetbrains.kotlin.psi.KtElement

/** One bounded artifact read serves callback and ordinary reviewed calls. */
internal data class NativeReviewedArtifactDigests(
    val binary: DependencyArtifactDigest,
    val sources: DependencyArtifactDigest,
)

internal fun readReviewedLibraryArtifact(
    element: KtElement,
    selectedFile: VirtualFile,
    sourceEntry: String,
    context: IntellijCallbackFlowContext,
): Refinement<NativeReviewedArtifactDigests, ReviewedCallbackValueFailure> {
    val binaryFile =
        VfsUtilCore.getVirtualFileForJar(selectedFile)
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
    val sourceRoot =
        when (
            val read =
                IntellijProjectLibrarySources.sourceRoot(
                    element.project,
                    selectedFile,
                    sourceEntry,
                ) {
                    reviewedPermit(context)
                }
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected ->
                return when (val failure = read.failure) {
                    LibrarySourceRootFailure.Unavailable,
                    LibrarySourceRootFailure.Ambiguous ->
                        Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
                    is LibrarySourceRootFailure.WorkRejected -> Refinement.Rejected(failure.failure)
                }
        }
    val sourceFile =
        VfsUtilCore.getVirtualFileForJar(sourceRoot)
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
    val binary =
        when (val read = reviewedArtifactDigest(binaryFile, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val sources =
        when (val read = reviewedArtifactDigest(sourceFile, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return Refinement.Refined(NativeReviewedArtifactDigests(binary, sources))
}

private fun reviewedArtifactDigest(
    file: VirtualFile,
    context: IntellijCallbackFlowContext,
): Refinement<DependencyArtifactDigest, ReviewedCallbackValueFailure> {
    if (file.length !in 1..MAX_REVIEWED_ARTIFACT_BYTES)
        return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
    val stamp = file.modificationStamp
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        when (val read = file.inputStream.use { input -> updateReviewedDigest(input, digest, context) }) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return read
        }
    } catch (_: IOException) {
        return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
    }
    return if (file.modificationStamp != stamp) Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_CHANGED)
    else DependencyArtifactDigest.parse(java.util.HexFormat.of().formatHex(digest.digest()))
}

private fun updateReviewedDigest(
    input: InputStream,
    digest: MessageDigest,
    context: IntellijCallbackFlowContext,
): Refinement<Unit, ReviewedCallbackValueFailure> {
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        ProgressManager.checkCanceled()
        when (val permit = reviewedPermit(context)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return permit
        }
        val count = input.read(chunk)
        if (count < 0) return Refinement.Refined(Unit)
        total += count
        if (total > MAX_REVIEWED_ARTIFACT_BYTES)
            return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_CHANGED)
        digest.update(chunk, 0, count)
    }
}

internal fun reviewedPermit(context: IntellijCallbackFlowContext): Refinement<Unit, ReviewedCallbackValueFailure> =
    when (context.claimWork()) {
        CallbackWorkAdmission.READY -> Refinement.Refined(Unit)
        CallbackWorkAdmission.WORK_LIMIT_REACHED -> Refinement.Rejected(ReviewedCallbackValueFailure.WORK_LIMIT_REACHED)
        CallbackWorkAdmission.TIME_LIMIT_REACHED -> Refinement.Rejected(ReviewedCallbackValueFailure.TIME_LIMIT_REACHED)
    }

private const val MAX_REVIEWED_ARTIFACT_BYTES = 8_388_608L
