package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
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

/** One owned source input uses the existing real K2 and canonical query composition. */
internal class NativeSemanticOwnersFixture(
    private val project: Project,
    private val module: Module,
    private val base: VirtualFile,
) {
    fun prepare(source: String, registerSdk: (Sdk) -> Unit): NativeScopedTraceFixture {
        val directories =
            WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                val src = base.createChildDirectory(this, "src")
                listOf(
                    src.createChildDirectory(this, "backend-service-clients"),
                    src.createChildDirectory(this, "unrelated"),
                )
            }
        configureModule(directories, registerSdk)
        val files = sourceFiles(directories, source)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).traceRefined()
        val model =
            WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                directories.mapIndexed { index, directory ->
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = base.toNioPath(),
                        gradleProjectPath = if (index == 0) ":backend-service-clients" else ":unrelated",
                        sourceSetName = "main",
                        sourceRoot = directory.toNioPath(),
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            ) as WorkspaceSearchScopeModelCompilation.Compiled
        return NativeScopedTraceFixture(project = project, root = root, model = model.model, files = files)
    }

    private fun configureModule(directories: List<VirtualFile>, registerSdk: (Sdk) -> Unit) {
        val sdk = JavaSdk.getInstance().createJdk("semantic-owners-jdk", System.getProperty("java.home"), false)
        registerSdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { native ->
            native.sdk = sdk
            val content = native.addContentEntry(base)
            directories.forEach { content.addSourceFolder(it, false) }
            val library = native.moduleLibraryTable.createLibrary("semantic-owners-stdlib")
            val roots = library.modifiableModel
            val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
            roots.commit()
        }
    }

    private fun sourceFiles(directories: List<VirtualFile>, source: String): List<VirtualFile> =
        WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
            listOf(
                directories[0].createChildData(this, "Owners.kt").also { it.setBinaryContent(source.toByteArray()) },
                directories[1].createChildData(this, "Outside.kt").also {
                    it.setBinaryContent("package unrelated\nclass Target\nfun target() {}\n".toByteArray())
                },
            )
        }
}
