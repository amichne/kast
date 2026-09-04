package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Path

/** Finite failures reported while proving imported source/exclusion compatibility. */
enum class IntellijIndexExclusionVerificationFailure {
    IMPORTED_MODULES_UNAVAILABLE,
    EXCLUSION_ROOT_UNAVAILABLE,
    EXCLUSION_NOT_PRESERVED,
    SOURCE_ROOT_NOT_ADMITTED,
    PLATFORM_OBSERVATION_FAILED,
}

/** Detached result of observing the live imported model through IntelliJ's file-index authority. */
sealed interface IntellijIndexExclusionVerification {
    data class Verified(
        val generatedSourceRootCount: Int,
    ) : IntellijIndexExclusionVerification

    data class Rejected(
        val failure: IntellijIndexExclusionVerificationFailure,
    ) : IntellijIndexExclusionVerification
}

/** Exclusive adapter for live `ProjectFileIndex` exclusion and source-root observations. */
object IntellijIndexExclusionVerifier {
    /**
     * Proves that planned roots remain excluded, no exclusion masks an imported source subtree,
     * and imported generated source roots nested beneath an exclusion remain source content.
     */
    fun verify(
        project: Project,
        bootstrapModule: Module,
        excludedDirectoryPaths: List<Path>,
    ): IntellijIndexExclusionVerification = try {
        ReadAction.computeBlocking<IntellijIndexExclusionVerification, RuntimeException> {
            val importedModules = ModuleManager.getInstance(project).modules
                .filter { candidate -> !candidate.isDisposed && candidate !== bootstrapModule }
            if (importedModules.isEmpty()) {
                return@computeBlocking rejected(
                    IntellijIndexExclusionVerificationFailure.IMPORTED_MODULES_UNAVAILABLE,
                )
            }
            val index = ProjectFileIndex.getInstance(project)
            val localFileSystem = LocalFileSystem.getInstance()
            val excludedRoots = excludedDirectoryPaths.map { path ->
                localFileSystem.findFileByNioFile(path)
                    ?: return@computeBlocking rejected(
                        IntellijIndexExclusionVerificationFailure.EXCLUSION_ROOT_UNAVAILABLE,
                    )
            }
            if (excludedRoots.any { root -> !index.isExcluded(root) }) {
                return@computeBlocking rejected(
                    IntellijIndexExclusionVerificationFailure.EXCLUSION_NOT_PRESERVED,
                )
            }
            val importedSourceRoots = importedModules
                .flatMap { importedModule ->
                    ModuleRootManager.getInstance(importedModule).sourceRoots.asList()
                }
                .map { sourceRoot ->
                    val sourcePath = VfsUtilCore.virtualToIoFile(sourceRoot)
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                    sourceRoot to sourcePath
                }
            if (
                importedSourceRoots.any { (_, sourcePath) ->
                    excludedDirectoryPaths.any { excludedPath -> excludedPath.startsWith(sourcePath) }
                }
            ) {
                return@computeBlocking rejected(
                    IntellijIndexExclusionVerificationFailure.SOURCE_ROOT_NOT_ADMITTED,
                )
            }
            val sourceRootsBelowExclusions = importedSourceRoots
                .filter { (_, sourcePath) -> excludedDirectoryPaths.any(sourcePath::startsWith) }
                .map { (sourceRoot, _) -> sourceRoot }
            if (sourceRootsBelowExclusions.any { sourceRoot -> !index.isInSourceContent(sourceRoot) }) {
                rejected(IntellijIndexExclusionVerificationFailure.SOURCE_ROOT_NOT_ADMITTED)
            } else {
                IntellijIndexExclusionVerification.Verified(sourceRootsBelowExclusions.size)
            }
        }
    } catch (_: RuntimeException) {
        rejected(IntellijIndexExclusionVerificationFailure.PLATFORM_OBSERVATION_FAILED)
    }

    private fun rejected(
        failure: IntellijIndexExclusionVerificationFailure,
    ): IntellijIndexExclusionVerification.Rejected =
        IntellijIndexExclusionVerification.Rejected(failure)
}
