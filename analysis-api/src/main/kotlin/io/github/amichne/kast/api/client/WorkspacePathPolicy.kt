package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.NormalizedPath
import java.nio.file.Path

@JvmInline
value class WorkspaceRelativePath private constructor(val value: String) {
    val path: Path get() = Path.of(value)

    companion object {
        fun parse(path: Path): WorkspaceRelativePath {
            require(!path.isAbsolute) { "Workspace-relative paths must not be absolute: $path" }
            val normalized = path.normalize()
            require(normalized.none { segment -> segment.toString() == ".." }) {
                "Workspace-relative paths must remain inside the workspace: $path"
            }
            return WorkspaceRelativePath(normalized.toString().replace('\\', '/'))
        }

        fun resolve(workspaceRoot: Path, candidate: Path): WorkspaceRelativePath? {
            val root = NormalizedPath.of(workspaceRoot).toJavaPath()
            val absoluteCandidate = if (candidate.isAbsolute) candidate else root.resolve(candidate)
            val canonicalCandidate = NormalizedPath.of(absoluteCandidate).toJavaPath()
            if (!canonicalCandidate.startsWith(root)) return null
            return parse(root.relativize(canonicalCandidate))
        }
    }
}

object WorkspacePathPolicy {
    private val hardExcludedDirectoryNames = setOf(".gradle", ".idea", ".kotlin", "build", "out")

    fun isHardExcluded(path: Path): Boolean =
        isHardExcluded(WorkspaceRelativePath.parse(path))

    fun isHardExcluded(path: WorkspaceRelativePath): Boolean =
        path.path.any { segment -> segment.toString() in hardExcludedDirectoryNames }
}
