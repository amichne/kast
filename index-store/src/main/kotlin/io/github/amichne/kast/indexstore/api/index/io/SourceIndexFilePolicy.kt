package io.github.amichne.kast.indexstore.api.index

import io.github.amichne.kast.api.client.WorkspacePathPolicy
import io.github.amichne.kast.api.client.WorkspaceRelativePath
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import java.nio.file.Path

class WorkspaceSourcePath private constructor(
    internal val workspaceRoot: NormalizedPath,
    val absolute: SemanticGraphPath,
    val relative: WorkspaceRelativePath,
) : Comparable<WorkspaceSourcePath> {
    val semanticGraphSourcePath: SemanticGraphSourcePath = SemanticGraphSourcePath.parse(relative.value)

    override fun compareTo(other: WorkspaceSourcePath): Int {
        val absoluteOrder = absolute.compareTo(other.absolute)
        if (absoluteOrder != 0) return absoluteOrder
        val workspaceOrder = workspaceRoot.compareTo(other.workspaceRoot)
        return if (workspaceOrder != 0) workspaceOrder else relative.value.compareTo(other.relative.value)
    }

    override fun equals(other: Any?): Boolean =
        other is WorkspaceSourcePath &&
            workspaceRoot == other.workspaceRoot &&
            absolute == other.absolute &&
            relative == other.relative

    override fun hashCode(): Int = 31 * (31 * workspaceRoot.hashCode() + absolute.hashCode()) + relative.hashCode()

    override fun toString(): String = absolute.value.value

    companion object {
        internal fun resolve(
            workspaceRoot: NormalizedPath,
            candidate: Path,
        ): WorkspaceSourcePath? = WorkspaceRelativePath.resolve(workspaceRoot.toJavaPath(), candidate)
            ?.let { relative -> create(workspaceRoot, relative) }

        internal fun resolve(
            workspaceRoot: NormalizedPath,
            relative: WorkspaceRelativePath,
        ): WorkspaceSourcePath? = resolve(
            workspaceRoot = workspaceRoot,
            candidate = workspaceRoot.toJavaPath().resolve(relative.path),
        )

        private fun create(
            workspaceRoot: NormalizedPath,
            relative: WorkspaceRelativePath,
        ): WorkspaceSourcePath? {
            if (!SourceIndexFilePolicy.isEligible(relative)) return null
            val root = workspaceRoot.toJavaPath()
            val absolute = SemanticGraphPath.parse(root.resolve(relative.path).normalize().toString())
            return WorkspaceSourcePath(workspaceRoot, absolute, relative)
        }
    }
}

object SourceIndexFilePolicy {
    fun isEligibleWorkspaceRelative(path: String): Boolean =
        runCatching { WorkspaceRelativePath.parse(Path.of(path)) }
            .getOrNull()
            ?.let(::isEligible) == true

    fun forWorkspace(workspaceRoot: Path): WorkspaceSourceIndexFilePolicy =
        WorkspaceSourceIndexFilePolicy(workspaceRoot)

    fun isEligible(path: WorkspaceRelativePath): Boolean =
        path.path.fileName?.toString()?.endsWith(".kt") == true &&
            !WorkspacePathPolicy.isHardExcluded(path)

}

class WorkspaceSourceIndexFilePolicy internal constructor(
    workspaceRoot: Path,
) {
    private val workspaceRoot = NormalizedPath.of(workspaceRoot)

    fun sourcePath(path: Path): WorkspaceSourcePath? = WorkspaceSourcePath.resolve(workspaceRoot, path)

    fun sourcePath(path: SemanticGraphPath): WorkspaceSourcePath? = sourcePath(path.value.toJavaPath())

    fun sourcePath(path: WorkspaceRelativePath): WorkspaceSourcePath? =
        WorkspaceSourcePath.resolve(workspaceRoot, path)

    fun isEligible(path: Path): Boolean = sourcePath(path) != null

    fun isEligible(path: String): Boolean = isEligible(Path.of(path))
}
