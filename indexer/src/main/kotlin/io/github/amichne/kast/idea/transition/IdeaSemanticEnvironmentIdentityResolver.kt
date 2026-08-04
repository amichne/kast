package io.github.amichne.kast.idea.transition

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ExportableOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver

internal object IdeaSemanticEnvironmentIdentityResolver {
    fun resolve(
        project: Project,
        workspaceIdentity: WorkspaceIdentity,
        isCancelled: () -> Boolean = { false },
    ): String =
        ApplicationManager.getApplication().runReadAction<String> {
            buildList {
                add("classpath:${BuildClasspathFingerprintResolver.resolve(project, workspaceIdentity, isCancelled).value}")
                val modules = ModuleManager.getInstance(project).modules
                    .filterNot { module -> module.isDisposed }
                    .sortedBy { module -> module.name }
                add("compiler:${IdeaKotlinCompilerIdentityResolver.resolve(project, modules, isCancelled)}")
                add(
                    "compiler-sources:${
                        IdeaCompilerVisibleSourceIdentityResolver.resolve(
                            project,
                            workspaceIdentity,
                            modules,
                            isCancelled,
                        ).value
                    }",
                )
                modules.forEach { module ->
                    val roots = ModuleRootManager.getInstance(module)
                    add("module:${module.name}")
                    add("sdk:${roots.sdk?.name.orEmpty()}:${roots.sdk?.versionString.orEmpty()}")
                    roots.sourceRoots.forEachIndexed { sourceIndex, root ->
                        add("source-index:$sourceIndex")
                        add("source:${root.url}")
                    }
                    roots.orderEntries
                        .forEachIndexed { orderIndex, entry ->
                            add("order-index:$orderIndex")
                            add("order-type:${entry.javaClass.name}")
                            add("order-name:${entry.presentableName}")
                            add("order-valid:${entry.isValid}")
                            (entry as? ExportableOrderEntry)?.let { exportable ->
                                add("order-scope:${exportable.scope.name}")
                                add("order-exported:${exportable.isExported}")
                            }
                            entry.getFiles(OrderRootType.CLASSES)
                                .forEachIndexed { classIndex, file ->
                                    add("class-index:$classIndex")
                                    add("class:${file.url}")
                                }
                        }
                }
            }.joinToString("\n")
        }
}
