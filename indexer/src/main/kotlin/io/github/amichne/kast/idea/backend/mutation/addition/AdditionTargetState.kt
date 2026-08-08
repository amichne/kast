package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal sealed interface AdditionTargetState {
    val editableTarget: EditableAdditionTarget

    val targetPath: Path
        get() = editableTarget.targetPath
}

internal class CreatableAdditionTarget private constructor(
    override val editableTarget: EditableAdditionTarget,
) : AdditionTargetState {
    companion object {
        /**
         * Proof transition: `EditableAdditionTarget -> CreatableAdditionTarget`.
         *
         * Establishes a canonical existing parent, canonical classified source root, absent target,
         * and containment of the prospective file beneath that root. Expected failures are closed
         * by `AdditionProofIncompleteException` and `AdditionProofLimitation`.
         */
        fun admit(target: EditableAdditionTarget): CreatableAdditionTarget {
            val normalizedTarget = target.targetPath
            val normalizedParent = normalizedTarget.parent ?: failAddition(
                AdditionProofLimitation.TARGET_PARENT_MISSING,
                "The add-file target has no parent directory",
            )
            if (!Files.isDirectory(normalizedParent, NOFOLLOW_LINKS)) failAddition(
                AdditionProofLimitation.TARGET_PARENT_MISSING,
                "The add-file target parent does not exist",
            )
            if (Files.exists(normalizedTarget, NOFOLLOW_LINKS)) failAddition(
                AdditionProofLimitation.TARGET_ALREADY_EXISTS,
                "The add-file target already exists",
            )
            val canonicalParent = try {
                normalizedParent.toRealPath()
            } catch (_: Exception) {
                failAddition(AdditionProofLimitation.TARGET_PARENT_MISSING, "The add-file parent is not canonical")
            }
            val sourceRoot = target.sourceRootPath
            val canonicalSourceRoot = try {
                sourceRoot.toRealPath()
            } catch (_: Exception) {
                failAddition(
                    AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                    "The model-owned source root is not canonical",
                )
            }
            val canonicalCandidate = canonicalParent.resolve(normalizedTarget.fileName).normalize()
            if (canonicalParent != normalizedParent || canonicalSourceRoot != sourceRoot ||
                canonicalCandidate != normalizedTarget || !canonicalCandidate.startsWith(canonicalSourceRoot)
            ) failAddition(
                AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                "The add-file target or its parent escapes the canonical model-owned source root",
            )
            return CreatableAdditionTarget(target)
        }
    }
}

internal class ExistingAdditionTarget private constructor(
    override val editableTarget: EditableAdditionTarget,
    exactPreimage: ByteArray,
) : AdditionTargetState {
    private val exactPreimage = exactPreimage.copyOf()

    /**
     * Boundary extraction: `ExistingAdditionTarget -> ByteArray`.
     *
     * Returns a defensive copy only for the text-image planner that consumes the already-proven
     * exact preimage; callers cannot mutate the capability's retained bytes.
     */
    fun copyPreimage(): ByteArray = exactPreimage.copyOf()

    companion object {
        /**
         * Proof transition: `EditableAdditionTarget -> ExistingAdditionTarget`.
         *
         * Establishes one canonical regular target beneath its canonical classified source root and
         * carries the exact securely read preimage. Expected failures are closed by
         * `AdditionProofIncompleteException` and `AdditionProofLimitation`; raw bytes are extracted
         * only through `copyPreimage` at the text-image planning boundary.
         */
        fun admit(backend: KastIndexerBackend, target: EditableAdditionTarget): ExistingAdditionTarget {
            val normalizedTarget = target.targetPath
            if (!Files.isRegularFile(normalizedTarget, NOFOLLOW_LINKS)) failAddition(
                AdditionProofLimitation.TARGET_FILE_MISSING,
                "The add-declaration target file does not exist",
            )
            val canonicalTarget = try {
                normalizedTarget.toRealPath()
            } catch (_: Exception) {
                failAddition(AdditionProofLimitation.TARGET_NOT_KOTLIN_SOURCE, "The target path is not canonical")
            }
            val sourceRoot = target.sourceRootPath
            val canonicalSourceRoot = try {
                sourceRoot.toRealPath()
            } catch (_: Exception) {
                failAddition(
                    AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                    "The model-owned source root is not canonical",
                )
            }
            if (canonicalTarget != normalizedTarget || canonicalSourceRoot != sourceRoot ||
                !canonicalTarget.startsWith(canonicalSourceRoot)
            ) failAddition(
                AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                "The add-declaration target escapes the canonical model-owned source root",
            )
            val exactPreimage = try {
                backend.exactFileImageMutation.readFileBytes(normalizedTarget, IdeaWorkspaceMutation.TEXT_EDIT)
            } catch (failure: ProcessCanceledException) {
                throw failure
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                failAddition(
                    AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
                    "An exact source-context image could not be read without following symbolic links",
                )
            }
            return ExistingAdditionTarget(target, exactPreimage)
        }
    }
}
