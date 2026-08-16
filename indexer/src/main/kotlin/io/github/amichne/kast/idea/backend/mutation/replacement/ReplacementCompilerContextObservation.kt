package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.result.ReplacementCompilerContext
import io.github.amichne.kast.api.contract.result.ReplacementCompilerModelGeneration
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal enum class ReplacementCompilerContextObservationFailure {
    SOURCE_ENUMERATION_FAILED,
    SOURCE_IMAGE_UNREADABLE,
    DUPLICATE_SOURCE_PATH,
    COMPILER_MODEL_CHANGED,
}

internal sealed interface ReplacementCompilerContextObservation {
    data class Proven(
        val context: ReplacementCompilerContext,
    ) : ReplacementCompilerContextObservation

    data class Rejected(
        val failure: ReplacementCompilerContextObservationFailure,
    ) : ReplacementCompilerContextObservation
}

/**
 * Proof transition: an exact excluded target path -> [ReplacementCompilerContextObservation].
 *
 * A [ReplacementCompilerContextObservation.Proven] result establishes an exact, canonical
 * path-to-SHA-256 image of every other Kotlin or Java [ProjectFileIndex] source-content file.
 * One [ProjectRootModificationTracker] generation stays stable across enumeration. Raw disk
 * changes to an admitted file are observed without VFS refresh. Undiscovered or non-admitted files
 * are outside this compiler-context claim until project admission changes the source set or model
 * generation. This capability does not claim diagnostics outside the target; target-body
 * diagnostic completeness is established separately. Expected enumeration, duplicate-path,
 * secure-read, and concurrent-model failures are closed by
 * [ReplacementCompilerContextObservation.Rejected]. Raw bytes are extracted only at the secure
 * workspace mutation boundary.
 */
internal fun KastIndexerBackend.observeReplacementCompilerContext(
    excludedTargetPath: ExactFileImagePath,
): ReplacementCompilerContextObservation {
    val root = workspaceIdentity.canonicalWorkspaceRootPath
    val excluded = Path.of(excludedTargetPath.value)
    val fileIndex = ProjectFileIndex.getInstance(project)
    val roots = ProjectRootModificationTracker.getInstance(project)
    val modelGeneration = roots.modificationCount
    var collection: CompilerContextCollection = CompilerContextCollection.Collecting(linkedMapOf())
    try {
        fileIndex.iterateContent { virtualFile ->
            when (val candidate = virtualFile.admitCompilerContextSource(fileIndex, root, excluded)) {
                CompilerContextSourceAdmission.Ignored -> true
                is CompilerContextSourceAdmission.Rejected -> {
                    collection = CompilerContextCollection.Rejected(candidate.failure)
                    false
                }

                is CompilerContextSourceAdmission.Candidate -> when (val current = collection) {
                    is CompilerContextCollection.Rejected -> false
                    is CompilerContextCollection.Collecting -> {
                        if (current.filesByPath.containsKey(candidate.filePath)) {
                            collection = CompilerContextCollection.Rejected(
                                ReplacementCompilerContextObservationFailure.DUPLICATE_SOURCE_PATH,
                            )
                            false
                        } else {
                            when (val image = readCompilerContextImage(candidate.path)) {
                                is CompilerContextImageAdmission.Admitted -> {
                                    current.filesByPath[candidate.filePath] = image.sha256
                                    true
                                }

                                is CompilerContextImageAdmission.Rejected -> {
                                    collection = CompilerContextCollection.Rejected(image.failure)
                                    false
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        collection = CompilerContextCollection.Rejected(
            ReplacementCompilerContextObservationFailure.SOURCE_ENUMERATION_FAILED,
        )
    }
    if (roots.modificationCount != modelGeneration) {
        return ReplacementCompilerContextObservation.Rejected(
            ReplacementCompilerContextObservationFailure.COMPILER_MODEL_CHANGED,
        )
    }
    return when (val collected = collection) {
        is CompilerContextCollection.Rejected ->
            ReplacementCompilerContextObservation.Rejected(collected.failure)

        is CompilerContextCollection.Collecting -> when (
            val generation = ReplacementCompilerModelGeneration.parse(modelGeneration)
        ) {
            is ReplacementContractAdmission.Admitted ->
                ReplacementCompilerContextObservation.Proven(
                    ReplacementCompilerContext.of(collected.filesByPath, generation.value),
                )

            is ReplacementContractAdmission.Rejected ->
                ReplacementCompilerContextObservation.Rejected(
                    ReplacementCompilerContextObservationFailure.SOURCE_ENUMERATION_FAILED,
                )
        }
    }
}

private sealed interface CompilerContextCollection {
    data class Collecting(
        val filesByPath: LinkedHashMap<ExactFileImagePath, ExactFileImageSha256>,
    ) : CompilerContextCollection

    data class Rejected(
        val failure: ReplacementCompilerContextObservationFailure,
    ) : CompilerContextCollection
}

private sealed interface CompilerContextSourceAdmission {
    data object Ignored : CompilerContextSourceAdmission

    data class Candidate(
        val path: Path,
        val filePath: ExactFileImagePath,
    ) : CompilerContextSourceAdmission

    data class Rejected(
        val failure: ReplacementCompilerContextObservationFailure,
    ) : CompilerContextSourceAdmission
}

private fun VirtualFile.admitCompilerContextSource(
    fileIndex: ProjectFileIndex,
    root: Path,
    excluded: Path,
): CompilerContextSourceAdmission {
    if (
        isDirectory ||
        !fileIndex.isInSourceContent(this) ||
        extension?.lowercase() !in COMPILER_SOURCE_EXTENSIONS
    ) {
        return CompilerContextSourceAdmission.Ignored
    }
    val path = try {
        Path.of(path).toAbsolutePath().normalize()
    } catch (_: Exception) {
        return CompilerContextSourceAdmission.Rejected(
            ReplacementCompilerContextObservationFailure.SOURCE_ENUMERATION_FAILED,
        )
    }
    return if (!path.startsWith(root) || path == excluded) {
        CompilerContextSourceAdmission.Ignored
    } else {
        CompilerContextSourceAdmission.Candidate(path, ExactFileImagePath(path.toString()))
    }
}

private sealed interface CompilerContextImageAdmission {
    data class Admitted(
        val sha256: ExactFileImageSha256,
    ) : CompilerContextImageAdmission

    data class Rejected(
        val failure: ReplacementCompilerContextObservationFailure,
    ) : CompilerContextImageAdmission
}

private fun KastIndexerBackend.readCompilerContextImage(path: Path): CompilerContextImageAdmission = try {
    val bytes = exactFileImageMutation.readFileBytes(path, IdeaWorkspaceMutation.TEXT_EDIT)
    CompilerContextImageAdmission.Admitted(ExactFileImageSha256(FileHashing.sha256(bytes)))
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    CompilerContextImageAdmission.Rejected(
        ReplacementCompilerContextObservationFailure.SOURCE_IMAGE_UNREADABLE,
    )
}

private val COMPILER_SOURCE_EXTENSIONS = setOf("kt", "java")
