package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

/** Indexed source files and an authored model belong to the native case. */
internal class NativeSourceContextFixture(
    private val project: Project,
    private val base: VirtualFile,
    private val module: Module,
    private val sdk: Sdk,
) {
    fun prepare(text: String): Pair<NativeScopedTraceFixture, VirtualFile> {
        val (fixture, files) = prepare(mapOf("Owners.kt" to text))
        return fixture to files.single()
    }

    fun prepare(sources: Map<String, String>): Pair<NativeScopedTraceFixture, List<VirtualFile>> {
        lateinit var result: Pair<NativeScopedTraceFixture, List<VirtualFile>>
        ApplicationManager.getApplication().invokeAndWait {
            val directory =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, "src")
                }
            ModuleRootModificationUtil.updateModel(module) { native ->
                native.sdk = sdk
                native.addContentEntry(base).addSourceFolder(directory, false)
                val library = native.moduleLibraryTable.createLibrary("source-context-stdlib")
                val roots = library.modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                roots.commit()
            }
            val files =
                WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                    sources.map { (name, text) ->
                        directory.createSourceFile(name).also { it.setBinaryContent(text.toByteArray()) }
                    }
                }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).traceRefined()
            val model =
                WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module.name,
                            linkedBuildRoot = base.toNioPath(),
                            gradleProjectPath = ":",
                            sourceSetName = "main",
                            sourceRoot = directory.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                ) as WorkspaceSearchScopeModelCompilation.Compiled
            result =
                NativeScopedTraceFixture(project = project, root = root, model = model.model, files = files) to files
        }
        return result
    }
}

private fun VirtualFile.createSourceFile(relative: String): VirtualFile {
    val segments = relative.split('/')
    val directory =
        segments.dropLast(1).fold(this) { current, segment ->
            current.findChild(segment) ?: current.createChildDirectory(this, segment)
        }
    return directory.createChildData(this, segments.last())
}
