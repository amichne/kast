package io.github.amichne.kast.change.verify.intellij

import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class EffectBoundVerifiedAddDeclarationTargetFailure {
    IDENTITY_CHANGED,
}

internal class EffectBoundVerifiedAddDeclarationTarget private constructor(
    val path: Path,
    private val bytes: ByteArray,
    private val capability: AddDeclarationTargetCapability,
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    /**
     * Proof transition:
     * `EffectBoundVerifiedAddDeclarationTarget ->
     * Refinement<EffectBoundVerifiedAddDeclarationTarget,
     * EffectBoundVerifiedAddDeclarationTargetFailure>`.
     *
     * Re-establishes that the planned workspace, source root, and target remain their exact real
     * non-symlink paths immediately before verification is returned. The closed expected failure
     * is [EffectBoundVerifiedAddDeclarationTargetFailure]; raw path extraction is permitted only
     * at the IntelliJ VFS and NIO verification boundaries.
     */
    fun revalidate(): Refinement<
        EffectBoundVerifiedAddDeclarationTarget,
        EffectBoundVerifiedAddDeclarationTargetFailure,
        > = when (exactPaths(capability)) {
        is Refinement.Refined -> Refinement.Refined(this)
        is Refinement.Rejected -> rejected()
    }

    companion object {
        /**
         * Proof transition:
         * `AddDeclarationTargetCapability ->
         * Refinement<EffectBoundVerifiedAddDeclarationTarget,
         * EffectBoundVerifiedAddDeclarationTargetFailure>`.
         *
         * Establishes exact real workspace, source-root, and target identity, containment, and a
         * regular non-symlink target before reading its physical bytes. The closed expected failure
         * is [EffectBoundVerifiedAddDeclarationTargetFailure]; raw bytes may be extracted only for
         * exact postimage and compiler-context comparison inside the scoped IntelliJ read.
         */
        fun read(
            capability: AddDeclarationTargetCapability,
        ): Refinement<
            EffectBoundVerifiedAddDeclarationTarget,
            EffectBoundVerifiedAddDeclarationTargetFailure,
            > = when (val admitted = exactPaths(capability)) {
            is Refinement.Rejected -> rejected()
            is Refinement.Refined -> try {
                Refinement.Refined(
                    EffectBoundVerifiedAddDeclarationTarget(
                        admitted.value.target,
                        Files.readAllBytes(admitted.value.target),
                        capability,
                    ),
                )
            } catch (_: IOException) {
                rejected()
            } catch (_: SecurityException) {
                rejected()
            }
        }

        private fun rejected(): Refinement.Rejected<EffectBoundVerifiedAddDeclarationTargetFailure> =
            Refinement.Rejected(EffectBoundVerifiedAddDeclarationTargetFailure.IDENTITY_CHANGED)
    }
}

private data class ExactVerifiedAddDeclarationPaths(val target: Path)

/**
 * Proof transition:
 * `AddDeclarationTargetCapability -> Refinement<ExactVerifiedAddDeclarationPaths,
 * EffectBoundVerifiedAddDeclarationTargetFailure>`.
 *
 * Refined proves that workspace, source root, and target still resolve to their planned exact real
 * non-symlink paths, preserve containment, and retain directory/file kinds. The closed expected
 * failure is [EffectBoundVerifiedAddDeclarationTargetFailure]; the target path may be extracted
 * only to construct the effect-bound target read capability.
 */
private fun exactPaths(
    capability: AddDeclarationTargetCapability,
): Refinement<ExactVerifiedAddDeclarationPaths, EffectBoundVerifiedAddDeclarationTargetFailure> {
    val workspace = Path.of(capability.workspaceRoot.value)
    val sourceRoot = Path.of(capability.owner.sourceRoot)
    val target = Path.of(capability.targetPath.value)
    if (
        Files.isSymbolicLink(workspace) ||
        Files.isSymbolicLink(sourceRoot) ||
        Files.isSymbolicLink(target)
    ) {
        return identityChanged()
    }
    val realWorkspace: Path
    val realSourceRoot: Path
    val realTarget: Path
    try {
        realWorkspace = workspace.toRealPath()
        realSourceRoot = sourceRoot.toRealPath()
        realTarget = target.toRealPath()
    } catch (_: IOException) {
        return identityChanged()
    } catch (_: SecurityException) {
        return identityChanged()
    }
    if (
        realWorkspace != workspace ||
        realSourceRoot != sourceRoot ||
        realTarget != target ||
        !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ||
        sourceRoot == workspace ||
        !sourceRoot.startsWith(workspace) ||
        target == sourceRoot ||
        !target.startsWith(sourceRoot)
    ) {
        return identityChanged()
    }
    return Refinement.Refined(ExactVerifiedAddDeclarationPaths(target))
}

private fun identityChanged(): Refinement.Rejected<EffectBoundVerifiedAddDeclarationTargetFailure> =
    Refinement.Rejected(EffectBoundVerifiedAddDeclarationTargetFailure.IDENTITY_CHANGED)
