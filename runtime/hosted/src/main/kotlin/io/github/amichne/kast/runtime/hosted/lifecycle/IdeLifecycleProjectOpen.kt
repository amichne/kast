package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.impl.OpenProjectTask
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
            runConfigurators = false
            runConversionBeforeOpen = false
            isNewProject = !Files.exists(directory)
            beforeOpen = { opening ->
                importScopes.add(InitialImportScope(opening))
                true
            }
            useDefaultProjectAsTemplate = false
        }
    )
}
