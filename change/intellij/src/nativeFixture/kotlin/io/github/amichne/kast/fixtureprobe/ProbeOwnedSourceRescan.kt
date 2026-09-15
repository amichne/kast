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
    OWNED_SOURCE_DIRECTORY_AND_FIXTURE_FILE
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
 * Select the fixed burst directory and edited fixture file instead of waiting for watcher intake. The platform also
 * marks their ancestors; this does not recursively dirty descendants. The existing refresh still scans cached roots.
 */
internal fun markOwnedFixtureSources(sandbox: ProbeSandbox): ProbeResult<ProbeOwnedSourceDirtyMark> {
    val path = sandbox.project.resolve("src/main/kotlin")
    val source = path.resolve("Fixture.kt")
    if (
        listOf(sandbox.project.resolve("src"), sandbox.project.resolve("src/main"), path, source)
            .any(Files::isSymbolicLink) ||
            !Files.isDirectory(path) ||
            path.toRealPath() != path ||
            !Files.isRegularFile(source) ||
            source.toRealPath() != source
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
    val file =
        LocalFileSystem.getInstance().findFileByNioFile(source) as? NewVirtualFile
            ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
    if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem || file.canonicalPath?.let(Path::of) != source)
        return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
    val marked = VfsUtil.markDirty(false, true, directory, file)
    if (marked != listOf(directory, file)) return ProbeResult.Rejected(ProbeFailure.SETUP_NATIVE_TASKS_UNAVAILABLE)
    return ProbeResult.Accepted(
        ProbeOwnedSourceDirtyMark(
            ProbeDirtyMarkScope.OWNED_SOURCE_DIRECTORY_AND_FIXTURE_FILE,
            ProbeDirtyMarkOutcome.COMPLETED,
        )
    )
}
