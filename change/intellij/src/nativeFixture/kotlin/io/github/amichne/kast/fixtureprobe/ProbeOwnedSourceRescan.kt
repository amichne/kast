package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Fixture lifecycle evidence; ordinary semantic requests never invoke this effect. */
@Serializable
internal enum class ProbeDirtyMarkScope {
    OWNED_SOURCE_DIRECTORY
}

@Serializable
internal enum class ProbeDirtyMarkOutcome {
    COMPLETED
}

internal data class ProbeOwnedSourceDirtyMark(
    val scope: ProbeDirtyMarkScope,
    val outcome: ProbeDirtyMarkOutcome,
)

internal data class ProbeSetupRefreshEvidence(
    val dirtyMark: ProbeOwnedSourceDirtyMark,
    val nativeTasks: ProbeSetupDrainState,
)

/**
 * Select the fixed burst directory for nonrecursive marking instead of waiting for watcher intake. The platform also
 * marks its ancestors; this does not recursively dirty descendants. The existing refresh still scans cached roots.
 */
internal fun markOwnedSourceDirectory(sandbox: ProbeSandbox): ProbeResult<ProbeOwnedSourceDirtyMark> {
    val path = sandbox.project.resolve("src/main/kotlin")
    if (
        listOf(sandbox.project.resolve("src"), sandbox.project.resolve("src/main"), path).any(Files::isSymbolicLink) ||
            !Files.isDirectory(path) ||
            path.toRealPath() != path
    )
        return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
    val directory =
        LocalFileSystem.getInstance().findFileByNioFile(path) as? NewVirtualFile
            ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
    if (
        !directory.isValid ||
            !directory.isDirectory ||
            !directory.isInLocalFileSystem ||
            directory.canonicalPath?.let(Path::of) != path
    )
        return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
    val marked = VfsUtil.markDirty(false, true, directory)
    if (marked.size != 1 || marked.single() != directory)
        return ProbeResult.Rejected(ProbeFailure.SETUP_NATIVE_TASKS_UNAVAILABLE)
    return ProbeResult.Accepted(
        ProbeOwnedSourceDirtyMark(ProbeDirtyMarkScope.OWNED_SOURCE_DIRECTORY, ProbeDirtyMarkOutcome.COMPLETED)
    )
}
