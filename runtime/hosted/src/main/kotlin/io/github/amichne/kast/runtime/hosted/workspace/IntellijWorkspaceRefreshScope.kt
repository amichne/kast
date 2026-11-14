package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Without a native watcher every refreshed root is re-listed, so read preparation refreshes imported inputs only. Runs
 * on the EDT after the root resolved; the result always has at least one session with at least one root.
 */
internal fun importedRefreshSessions(
    project: Project,
    root: CanonicalWorkspaceRoot,
    directory: VirtualFile,
): List<RefreshSessionRoots> {
    val files = LocalFileSystem.getInstance()
    val found = HashMap<String, VirtualFile?>()
    val inputs = ReadAction.compute<WorkspaceRefreshInputs, RuntimeException> { workspaceRefreshInputs(project, root) }
    val scope = planWorkspaceRefresh(root, inputs) { found.getOrPut(it) { files.findFileByPath(it) } != null }
    Logger.getInstance(IntellijWorkspaceRefreshPort::class.java)
        .info("kast_vfs_refresh_scope " + Json.encodeToString(scope.observation()))
    return when (scope) {
        WorkspaceRefreshScope.WholeWorkspace -> listOf(RefreshSessionRoots(recursive = true, listOf(directory)))
        is WorkspaceRefreshScope.Imported -> {
            fun resolve(paths: List<String>) = paths.map {
                if (it == root.value) directory else checkNotNull(found[it])
            }
            listOf(
                    RefreshSessionRoots(recursive = false, resolve(scope.flat)),
                    RefreshSessionRoots(recursive = true, resolve(scope.recursive)),
                )
                .filter { it.roots.isNotEmpty() }
        }
    }
}

/** Imported ownership as declared by the project model and Gradle settings; reads no file content. */
private fun workspaceRefreshInputs(project: Project, root: CanonicalWorkspaceRoot): WorkspaceRefreshInputs {
    val sourceRoots =
        ModuleManager.getInstance(project).modules.flatMap { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap { entry ->
                entry.sourceFolders.map { VfsUtilCore.urlToPath(it.url) }
            }
        }
    val libraries = OrderEnumerator.orderEntries(project).withoutSdk().librariesOnly()
    val libraryRoots =
        (libraries.classes().roots + libraries.sources().roots).mapNotNull { library ->
            VfsUtil.getLocalFile(library).takeIf { it.isInLocalFileSystem }?.path
        }
    // Without a registered Gradle integration there is no linked build and no Gradle model input.
    val gradle = ExternalSystemApiUtil.getManager(IntellijWorkspaceRefreshPort.GRADLE)
    val linked = gradle?.let {
        ExternalSystemApiUtil.getSettings(project, IntellijWorkspaceRefreshPort.GRADLE)
            .getLinkedProjectSettings(root.value)
    }
    val awareness = gradle as? ExternalSystemAutoImportAware
    return WorkspaceRefreshInputs(
        sourceRoots = sourceRoots,
        libraryRoots = libraryRoots,
        modelDirectories = linked?.modules.orEmpty().map(FileUtil::toSystemIndependentName),
        modelFiles =
            if (linked == null || awareness == null) emptyList()
            else
                awareness.getAffectedExternalProjectFiles(root.value, project).map {
                    FileUtil.toSystemIndependentName(it.path)
                },
    )
}
