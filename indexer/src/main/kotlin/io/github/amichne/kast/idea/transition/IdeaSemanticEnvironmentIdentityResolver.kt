package io.github.amichne.kast.idea.transition

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver

internal object IdeaSemanticEnvironmentIdentityResolver {
    fun resolve(project: Project, workspaceIdentity: WorkspaceIdentity): String =
        ApplicationManager.getApplication().runReadAction<String> {
            buildList {
                add("classpath:${BuildClasspathFingerprintResolver.resolve(project, workspaceIdentity).value}")
                ModuleManager.getInstance(project).modules
                    .filterNot { module -> module.isDisposed }
                    .sortedBy { module -> module.name }
                    .forEach { module ->
                        val roots = ModuleRootManager.getInstance(module)
                        add("module:${module.name}")
                        add("sdk:${roots.sdk?.name.orEmpty()}:${roots.sdk?.versionString.orEmpty()}")
                        roots.sourceRoots.map { root -> root.url }.sorted().forEach { root -> add("source:$root") }
                        roots.orderEntries
                            .sortedBy { entry -> entry.presentableName }
                            .forEach { entry ->
                                add("order:${entry.presentableName}:${entry.isValid}")
                                entry.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES)
                                    .asSequence()
                                    .map { file -> file.url }
                                    .sorted()
                                    .forEach { file -> add("class:$file") }
                            }
                    }
            }.joinToString("\n")
        }
}
