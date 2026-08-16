package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path

/** Exact detached values consumed by composition's installed Gradle-model projection. */
class InstalledGradleModelCapture internal constructor(
    val root: CanonicalWorkspaceRoot,
    val sourceRoots: List<WorkspaceSourceRootBoundary>,
    val identityFields: List<String>,
)

/**
 * Proof transition: `(Project, Path) -> InstalledGradleModelCapture?`.
 *
 * A non-null result establishes a complete successful external-project model, non-empty exact
 * Gradle source-set ownership, and non-empty project/classpath identity fields. Private nullable
 * failure becomes [InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE] at the caller. Live
 * project, module, DataNode, source-root, SDK, and order-entry values do not leave this read action.
 */
internal fun captureInstalledGradleModel(
    project: Project,
    workspaceRoot: Path,
): InstalledGradleModelCapture? = ReadAction.nonBlocking<InstalledGradleModelCapture?> model@{
    val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRoot)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return@model null
    }
    val projects = ProjectDataManager.getInstance()
        .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
        .filter { info -> Path.of(info.externalProjectPath).toAbsolutePath().normalize() == workspaceRoot }
    if (projects.isEmpty() || projects.any { !it.isComplete() }) return@model null
    val boundaries = projects.flatMap { info -> info.sourceRootBoundaries() }
        .distinct()
        .sortedWith(compareBy({ it.sourceRoot.toString() }, { it.ideaModuleName }))
    if (boundaries.isEmpty()) return@model null
    val identities = buildList {
        projects.sortedBy(ExternalProjectInfo::getExternalProjectPath).forEach { info ->
            add("project:${info.externalProjectPath}")
            add("import:${info.lastSuccessfulImportTimestamp}")
        }
        ModuleManager.getInstance(project).modules
            .filterNot { it.isDisposed }
            .sortedBy { it.name }
            .forEach { module ->
                val roots = ModuleRootManager.getInstance(module)
                add("module:${module.name}")
                roots.sdk?.let { sdk -> add("sdk:${module.name}:${sdk.name}:${sdk.versionString}") }
                roots.orderEntries.sortedBy { it.presentableName }.forEach { entry ->
                    add("order:${module.name}:${entry.presentableName}:${entry.isValid}")
                }
            }
    }.distinct().sorted()
    if (identities.isEmpty() || identities.any(String::isBlank)) return@model null
    InstalledGradleModelCapture(root, boundaries, identities)
}.inSmartMode(project).executeSynchronously()

private fun ExternalProjectInfo.isComplete(): Boolean =
    externalProjectStructure?.let { structure ->
        structure.isReady &&
            lastSuccessfulImportTimestamp > 0 &&
            lastSuccessfulImportTimestamp >= lastImportTimestamp
    } == true

private fun ExternalProjectInfo.sourceRootBoundaries(): List<WorkspaceSourceRootBoundary> {
    val structure = externalProjectStructure ?: return emptyList()
    val boundaries = mutableListOf<WorkspaceSourceRootBoundary>()
    structure.visit { node ->
        val sourceSet = node.data as? GradleSourceSetData ?: return@visit
        boundaries += sourceSet.sourceRootBoundaries(node)
    }
    return boundaries
}

private fun GradleSourceSetData.sourceRootBoundaries(
    node: DataNode<*>,
): List<WorkspaceSourceRootBoundary> {
    val separator = externalName.lastIndexOf(':')
    val projectPath = externalName.take(separator.coerceAtLeast(0)).ifEmpty { ":" }
    val sourceSetName = externalName.drop(separator + 1)
    val buildRoot = Path.of(linkedExternalProjectPath).toAbsolutePath().normalize()
    val boundaries = mutableListOf<WorkspaceSourceRootBoundary>()
    node.visit { child ->
        if (child.key != ProjectKeys.CONTENT_ROOT) return@visit
        val content = child.data as? ContentRootData ?: return@visit
        ExternalSystemSourceType.entries.forEach { type ->
            val kind = type.sourceKind() ?: return@forEach
            content.getPaths(type).forEach { sourceRoot ->
                boundaries += WorkspaceSourceRootBoundary(
                    internalName,
                    buildRoot,
                    projectPath,
                    sourceSetName,
                    Path.of(sourceRoot.path).toAbsolutePath().normalize(),
                    kind,
                    if (type.isGenerated) {
                        WorkspaceSourceRootProvenance.GENERATED
                    } else {
                        WorkspaceSourceRootProvenance.AUTHORED
                    },
                )
            }
        }
    }
    return boundaries
}

private fun ExternalSystemSourceType.sourceKind(): WorkspaceSourceRootKind? = when (this) {
    ExternalSystemSourceType.SOURCE,
    ExternalSystemSourceType.SOURCE_GENERATED,
        -> WorkspaceSourceRootKind.PRODUCTION
    ExternalSystemSourceType.TEST,
    ExternalSystemSourceType.TEST_GENERATED,
        -> WorkspaceSourceRootKind.TEST
    ExternalSystemSourceType.EXCLUDED,
    ExternalSystemSourceType.RESOURCE,
    ExternalSystemSourceType.TEST_RESOURCE,
    ExternalSystemSourceType.RESOURCE_GENERATED,
    ExternalSystemSourceType.TEST_RESOURCE_GENERATED,
        -> null
}
