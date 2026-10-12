package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal sealed interface PublicResetAdmission {
    data class Admitted(val scope: PublicResetScope) : PublicResetAdmission

    data class Rejected(val failure: ForceResetFailure) : PublicResetAdmission
}

/** Explicit public reset authority covers only the user installation and its historical local artifacts. */
internal class PublicResetScope private constructor(val root: Path, val home: Path, val command: Path) {
    companion object {
        fun admit(root: Path, environment: Map<String, String>): PublicResetAdmission {
            val home =
                try {
                    Path.of(environment["HOME"] ?: return rejected())
                } catch (_: IllegalArgumentException) {
                    return rejected()
                }
            if (root != home.resolve(".local/share/kast")) return rejected()
            val command =
                when (
                    val selection =
                        selectDestination(home.toString(), environment["XDG_CONFIG_HOME"], environment["PATH"])
                ) {
                    is DestinationSelection.Selected -> selection.executable
                    is DestinationSelection.Rejected -> return rejected()
                }
            for (path in listOf(root, home.resolve(".kast"), home.resolve(".local/bin/kast"), command)) {
                if (ForceResetRoot.admit(path, home) == ForceResetRootAdmission.Rejected) return rejected()
            }
            for (path in setOf(home.resolve(".local/bin/kast"), command)) {
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) return rejected()
                val ancestor = generateSequence(path.parent) { it.parent }.first { Files.exists(it, NOFOLLOW_LINKS) }
                if (!Files.isWritable(ancestor)) return rejected()
            }
            return PublicResetAdmission.Admitted(PublicResetScope(root, home, command))
        }

        private fun rejected() = PublicResetAdmission.Rejected(ForceResetFailure.ROOT_REJECTED)
    }

    fun removeExterior(erased: ErasedReset, runtime: ForceDaemonRuntime): ResetEffect =
        erased.fence.root.erasePublicExterior(this, erased, runtime)
}

internal sealed interface ResetRemovalScope {
    data object SelectedRoot : ResetRemovalScope

    data class Public(val scope: PublicResetScope) : ResetRemovalScope
}

internal fun resetPublicInstallation(
    scope: PublicResetScope,
    execution: ForceResetExecution = ForceResetExecution(),
): ForceResetOutcome =
    forceResetInstallation(
        scope.root,
        scope.home,
        emptyMap(),
        ForceResetOperation.UNINSTALL,
        execution,
        ResetRemovalScope.Public(scope),
    )
