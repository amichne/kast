package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun openNativeWorkspace(
    root: CanonicalWorkspaceRoot,
    importScopes: MutableList<InitialImportScope>,
): Project? =
    ProjectManagerEx.getInstanceEx()
        .openProjectAsync(
            Path.of(root.value),
            OpenProjectTask {
                forceOpenInNewFrame = true
                forceReuseFrame = false
                projectToClose = null
                showWelcomeScreen = false
                runConfigurators = false
                runConversionBeforeOpen = false
                isNewProject = !Files.exists(Path.of(root.value).resolve(".idea"))
                beforeOpen = { opening ->
                    importScopes.add(InitialImportScope(opening))
                    true
                }
                useDefaultProjectAsTemplate = false
            },
        )
