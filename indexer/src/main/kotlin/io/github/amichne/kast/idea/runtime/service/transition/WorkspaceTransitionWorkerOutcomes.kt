package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity

internal enum class WorkspaceWorkerWaitOutcome {
    Continue,
    Interrupted,
}

internal class BuildSemanticInputsMovedDuringRefreshException(
    val before: BuildSemanticInputIdentity,
    val after: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs moved during Gradle refresh")

internal class BuildSemanticModelStaleException(
    val imported: BuildSemanticInputIdentity,
    val current: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs do not match the imported Gradle model")
