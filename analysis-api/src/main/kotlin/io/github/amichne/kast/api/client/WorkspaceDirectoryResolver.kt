package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Path

@JvmInline
internal value class WorkspacePathKey private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `NormalizedPath -> WorkspacePathKey`.
         *
         * Derives the full stable SHA-256 workspace key from one canonical
         * workspace path. The digest is extracted only while constructing the
         * workspace data-directory path.
         */
        fun fromCanonicalPath(path: NormalizedPath): WorkspacePathKey = WorkspacePathKey(
            FileHashing.sha256(path.value),
        )
    }
}

@JvmInline
internal value class RepositoryPathKey private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `NormalizedPath -> RepositoryPathKey`.
         *
         * Derives the full stable SHA-256 repository key from the canonical Git
         * common-directory authority. The digest is extracted only while
         * constructing the repository data-directory path.
         */
        fun fromCommonDirectory(path: NormalizedPath): RepositoryPathKey = RepositoryPathKey(
            FileHashing.sha256(path.value),
        )
    }
}

internal data class WorkspaceDirectoryLayout(
    val repository: WorkspaceRepository,
    val workspaceDataDirectory: NormalizedPath,
) {
    val workspaceCacheDirectory: NormalizedPath
        get() = NormalizedPath.ofAbsolute(workspaceDataDirectory.toJavaPath().resolve("cache"))

    val workspaceDatabasePath: NormalizedPath
        get() = NormalizedPath.ofAbsolute(workspaceCacheDirectory.toJavaPath().resolve("source-index.db"))
}

class WorkspaceDirectoryResolver(
    private val installRoot: () -> Path = ::kastInstallRoot,
    private val dataRoot: () -> Path = { kastDataRoot(System::getenv, installRoot()) },
    private val gitWorkspaceResolver: (Path) -> GitWorkspace? = GitWorkspaceResolver::discover,
) {
    /**
     * Proof transition: `Path -> WorkspaceDirectoryLayout`.
     *
     * Canonicalizes the workspace path and derives its full-digest workspace
     * directory plus a closed Git-repository authority. The returned layout,
     * rather than the input path, is consumed by workspace identity creation.
     * Raw paths are exposed only by the public filesystem adapter methods.
     */
    internal fun resolveLayout(workspaceRoot: Path): WorkspaceDirectoryLayout {
        val canonicalRoot = NormalizedPath.of(workspaceRoot)
        val gitWorkspace = gitWorkspaceResolver(canonicalRoot.toJavaPath())
        val repository = gitWorkspace?.let {
            WorkspaceRepository.Git(gitRepositoryDataDirectory(it))
        } ?: WorkspaceRepository.None
        val workspaceDataDirectory = NormalizedPath.ofAbsolute(
            workspacesRoot()
                .resolve(WorkspacePathKey.fromCanonicalPath(canonicalRoot).value),
        )
        return WorkspaceDirectoryLayout(
            repository = repository,
            workspaceDataDirectory = workspaceDataDirectory,
        )
    }

    fun workspaceDataDirectory(workspaceRoot: Path): Path =
        resolveLayout(workspaceRoot).workspaceDataDirectory.toJavaPath()

    /**
     * Proof transition: `Path -> WorkspaceRepository`.
     *
     * Derives either an exact keyed Git-repository data authority or explicit
     * non-repository state; callers do not infer repository absence from null.
     */
    fun repository(workspaceRoot: Path): WorkspaceRepository =
        resolveLayout(workspaceRoot).repository

    fun workspaceCacheDirectory(workspaceRoot: Path): Path =
        resolveLayout(workspaceRoot).workspaceCacheDirectory.toJavaPath()

    fun workspaceDatabasePath(workspaceRoot: Path): Path =
        resolveLayout(workspaceRoot).workspaceDatabasePath.toJavaPath()

    fun workspaceIdentity(
        workspaceRoot: Path,
        descriptorDirectory: Path = defaultDescriptorDirectory(),
    ): WorkspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot, this, descriptorDirectory)

    fun workspaceHash(workspaceRoot: Path): String = FileHashing.sha256(
        workspaceRoot.toAbsolutePath().normalize().toString(),
    ).take(12)

    private fun workspacesRoot(): Path = dataRoot().resolve("workspaces").toAbsolutePath().normalize()

    private fun gitRepositoryDataDirectory(workspace: GitWorkspace): NormalizedPath =
        NormalizedPath.ofAbsolute(
            dataRoot()
                .resolve("repositories")
                .resolve(RepositoryPathKey.fromCommonDirectory(NormalizedPath.of(workspace.commonDir)).value),
        )
}

fun workspaceDataDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceDataDirectory(workspaceRoot)

fun workspaceCacheDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceCacheDirectory(workspaceRoot)

fun workspaceDatabasePath(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceDatabasePath(workspaceRoot)
