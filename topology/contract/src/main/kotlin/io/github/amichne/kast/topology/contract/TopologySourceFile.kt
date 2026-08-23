package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Path

enum class TopologySourceFileFailure {
    SOURCE_ROOT_NOT_PUBLISHED,
    FILE_OUTSIDE_SOURCE_ROOT,
    NOT_KOTLIN_SOURCE,
}

/** One content-identified Kotlin file owned by an admitted Gradle source set. */
@ConsistentCopyVisibility
data class TopologySourceFile private constructor(
    val workspace: TopologyWorkspaceIdentity,
    val sourceRoot: SourceRoot,
    val path: WorkspaceSourcePath,
    val contentHash: WorkspaceSourceContentHash,
) : Comparable<TopologySourceFile> {
    override fun compareTo(other: TopologySourceFile): Int = SOURCE_FILE_ORDER.compare(this, other)

    fun canonicalProjection(): String = buildString {
        appendTopologyField(path.value)
        appendTopologyField(contentHash.value)
        appendTopologyField(sourceRoot.owner.module.value)
        appendTopologyField(sourceRoot.owner.project.buildRoot.value)
        appendTopologyField(sourceRoot.owner.project.projectPath.value)
        appendTopologyField(sourceRoot.owner.sourceSet.value)
        appendTopologyField(sourceRoot.location.value)
        appendTopologyField(sourceRoot.provenance.canonicalName())
    }

    companion object {
        /**
         * Proof transition: `(PublishedWorkspace, SourceRoot, WorkspaceSourcePath,
         * WorkspaceSourceContentHash) -> Refinement<TopologySourceFile,
         * TopologySourceFileFailure>`.
         *
         * Establishes that the content-identified `.kt` or `.kts` file is below one source root
         * carried by the exact published workspace. [TopologySourceFileFailure] is the closed
         * expected failure. Raw path and digest extraction may occur only in the physical
         * admitted-root enumeration adapter.
         */
        fun admit(
            workspace: PublishedWorkspace,
            sourceRoot: SourceRoot,
            path: WorkspaceSourcePath,
            contentHash: WorkspaceSourceContentHash,
        ): Refinement<TopologySourceFile, TopologySourceFileFailure> {
            if (sourceRoot !in workspace.sourceRoots) {
                return Refinement.Rejected(
                    TopologySourceFileFailure.SOURCE_ROOT_NOT_PUBLISHED,
                )
            }
            return restore(TopologyWorkspaceIdentity.from(workspace), sourceRoot, path, contentHash)
        }

        /**
         * Proof transition: `(TopologyWorkspaceIdentity, SourceRoot, WorkspaceSourcePath,
         * WorkspaceSourceContentHash) -> Refinement<TopologySourceFile,
         * TopologySourceFileFailure>`.
         *
         * Re-establishes Kotlin extension and source-root containment for one file read from an
         * already published snapshot. [TopologySourceFileFailure] is the closed expected failure.
         * Raw persisted fields must first pass their workspace and source-root parsers at the
         * SQLite boundary.
         */
        fun restore(
            workspace: TopologyWorkspaceIdentity,
            sourceRoot: SourceRoot,
            path: WorkspaceSourcePath,
            contentHash: WorkspaceSourceContentHash,
        ): Refinement<TopologySourceFile, TopologySourceFileFailure> {
            val filePath = Path.of(path.value)
            val rootPath = Path.of(sourceRoot.location.value)
            val withinRoot = sourceRoot.location.value == "." ||
                             filePath != rootPath && filePath.startsWith(rootPath)
            if (!withinRoot) {
                return Refinement.Rejected(TopologySourceFileFailure.FILE_OUTSIDE_SOURCE_ROOT)
            }
            if (!path.value.endsWith(".kt") && !path.value.endsWith(".kts")) {
                return Refinement.Rejected(TopologySourceFileFailure.NOT_KOTLIN_SOURCE)
            }
            return Refinement.Refined(TopologySourceFile(workspace, sourceRoot, path, contentHash))
        }

        private val SOURCE_FILE_ORDER = compareBy<TopologySourceFile>(
            { it.path.value },
            { it.sourceRoot.owner.project.buildRoot.value },
            { it.sourceRoot.owner.project.projectPath.value },
            { it.sourceRoot.owner.sourceSet.value },
            { it.sourceRoot.owner.module.value },
        )
    }
}

private fun io.github.amichne.kast.workspace.contract.SourceRootProvenance.canonicalName(): String =
    when (this) {
        io.github.amichne.kast.workspace.contract.SourceRootProvenance.Authored -> "authored"
        io.github.amichne.kast.workspace.contract.SourceRootProvenance.Generated -> "generated"
        is io.github.amichne.kast.workspace.contract.SourceRootProvenance.Unknown ->
            when (reason) {
                io.github.amichne.kast.workspace.contract.ProvenanceFailure.ExcludedFromSourceModel ->
                    "unknown:excluded-from-source-model"
            }
    }

internal fun StringBuilder.appendTopologyField(value: String) {
    append(value.toByteArray(Charsets.UTF_8).size)
    append(':')
    append(value)
}
