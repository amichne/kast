package io.github.amichne.kast.source.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.workspace.contract.CanonicalSemanticProjectRoot
import java.nio.file.Path

/** Installed source-read port that locates one already-open exact-root IntelliJ project. */
class InstalledIntellijSourceReadPort private constructor(private val delegate: SourceReadPort) :
    SourceReadPort by delegate {
    companion object {
        fun create(root: CanonicalSemanticProjectRoot): InstalledIntellijSourceReadPort =
            InstalledIntellijSourceReadPort(
                IntellijSourceReadPort(
                    IntellijSourceRegionAccess { context, request, cursor ->
                        when (context.lease.requirePublished()) {
                            is Refinement.Refined -> Unit
                            is Refinement.Rejected ->
                                return@IntellijSourceRegionAccess regionRejected(
                                    IntellijSourceReadRejection.STALE_GENERATION
                                )
                        }
                        val project =
                            exactProject(root)
                                ?: return@IntellijSourceRegionAccess regionRejected(
                                    IntellijSourceReadRejection.SOURCE_UNAVAILABLE
                                )
                        LiveIntellijSourceRegionAccess(project).select(context, request, cursor)
                    }
                )
            )
    }
}

private fun exactProject(root: CanonicalSemanticProjectRoot): Project? =
    ProjectManager.getInstance().openProjects.singleOrNull { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()?.toString() == root.value
    }

private fun regionRejected(reason: IntellijSourceReadRejection) = IntellijSourceRegionAccessResult.Rejected(reason)
