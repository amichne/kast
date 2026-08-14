package io.github.amichne.kast.change.apply.intellij

import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class EffectBoundAddDeclarationTargetFailure {
    IDENTITY_CHANGED,
}

internal class EffectBoundAddDeclarationTarget private constructor(
    val path: Path,
    private val capability: AddDeclarationTargetCapability,
) {
    /**
     * Proof transition:
     * `EffectBoundAddDeclarationTarget -> Refinement<EffectBoundAddDeclarationTarget,
     * EffectBoundAddDeclarationTargetFailure>`.
     *
     * Re-establishes that the approved workspace, source root, and target remain their exact real
     * non-symlink paths immediately before the source effect. The closed expected failure is
     * `EffectBoundAddDeclarationTargetFailure`; raw path extraction is permitted only at the
     * IntelliJ VFS and NIO source-effect boundary.
     */
    fun revalidate(): Refinement<
        EffectBoundAddDeclarationTarget,
        EffectBoundAddDeclarationTargetFailure,
    > = admit(capability)

    companion object {
        /**
         * Proof transition:
         * `AddDeclarationTargetCapability -> Refinement<EffectBoundAddDeclarationTarget,
         * EffectBoundAddDeclarationTargetFailure>`.
         *
         * Establishes that the approved workspace and source root are exact real directories and
         * that the target is the exact real, regular, non-symlink file still contained by both.
         * The closed expected failure is `EffectBoundAddDeclarationTargetFailure`; raw path
         * extraction is permitted only at the IntelliJ VFS and NIO source-effect boundary.
         */
        fun admit(
            capability: AddDeclarationTargetCapability,
        ): Refinement<EffectBoundAddDeclarationTarget, EffectBoundAddDeclarationTargetFailure> {
            val workspace = Path.of(capability.workspaceRoot.value)
            val sourceRoot = Path.of(capability.owner.sourceRoot)
            val target = Path.of(capability.targetPath.value)
            if (
                Files.isSymbolicLink(workspace) ||
                Files.isSymbolicLink(sourceRoot) ||
                Files.isSymbolicLink(target)
            ) {
                return rejected()
            }
            val realWorkspace: Path
            val realSourceRoot: Path
            val realTarget: Path
            try {
                realWorkspace = workspace.toRealPath()
                realSourceRoot = sourceRoot.toRealPath()
                realTarget = target.toRealPath()
            } catch (_: IOException) {
                return rejected()
            } catch (_: SecurityException) {
                return rejected()
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
                return rejected()
            }
            return Refinement.Refined(EffectBoundAddDeclarationTarget(target, capability))
        }

        private fun rejected(): Refinement.Rejected<EffectBoundAddDeclarationTargetFailure> =
            Refinement.Rejected(EffectBoundAddDeclarationTargetFailure.IDENTITY_CHANGED)
    }
}
