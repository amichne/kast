package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path

/** Native opening keeps one project lifecycle and admits private metadata before the first project effect. */
internal fun nativeLifecycleProjectTask(
    root: CanonicalWorkspaceRoot,
    importScopes: MutableList<InitialImportScope>,
): Refinement<OpenProjectTask, IdeLifecycleFailure> {
    val application = com.intellij.openapi.application.ApplicationManager.getApplication()
    val directory =
        if (application.isHeadlessEnvironment) {
            val host =
                ManagedHeadlessHost.current() ?: return Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE)
            when (val storage = host.admitProjectStore(root)) {
                is Refinement.Rejected -> return storage
                is Refinement.Refined -> storage.value.directory
            }
        } else Path.of(root.value).resolve(".idea")
    return Refinement.Refined(
        OpenProjectTask {
            forceOpenInNewFrame = !application.isHeadlessEnvironment
            forceReuseFrame = false
            projectToClose = null
            showWelcomeScreen = false
            // Direct ProjectManager opening treats both SDK defaults as disabled.
            // The SDK's cache-empty override remains independent of this option.
            runConversionBeforeOpen = false
            isNewProject = !Files.exists(directory)
            beforeOpen = { opening ->
                importScopes.add(InitialImportScope(opening, deferScanning = application.isHeadlessEnvironment))
                true
            }
            useDefaultProjectAsTemplate = false
        }
    )
}

internal fun existingProjects(root: CanonicalWorkspaceRoot): List<Project> =
    ProjectManager.getInstance().openProjects.filter {
        !it.isDisposed && it.basePath?.let(::canonicalLifecycleRoot) == Refinement.Refined(root)
    }

internal suspend fun openNative(
    root: CanonicalWorkspaceRoot,
    importScopes: MutableList<InitialImportScope>,
): Refinement<Project, IdeLifecycleFailure> {
    val task =
        when (val admitted = nativeLifecycleProjectTask(root, importScopes)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val project = ProjectManagerEx.getInstanceEx().openProjectAsync(Path.of(root.value), task)
    return if (project == null) Refinement.Rejected(IdeLifecycleFailure.OPEN_FAILED) else Refinement.Refined(project)
}
