package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
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

/** Three native modules preserve client dependencies without a relay-to-signal dependency. */
internal class NativeCrossModuleCallbackFixture(
    private val project: Project,
    private val base: VirtualFile,
    private val clientModule: Module,
) {
    fun prepare(supply: CrossModuleCallbackSupply, registerSdk: (Sdk) -> Unit): NativeScopedTraceFixture {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            val sdk =
                JavaSdk.getInstance().createJdk("cross-module-callback-jdk", System.getProperty("java.home"), false)
            registerSdk(sdk)
            val units =
                WriteCommandAction.writeCommandAction(project).compute<List<UnitRoot>, RuntimeException> {
                    val manager = ModuleManager.getInstance(project)
                    CrossModuleCallbackUnit.entries.map { unit ->
                        val directory = base.createChildDirectory(this, unit.directory)
                        val native =
                            when (unit) {
                                CrossModuleCallbackUnit.CLIENT -> clientModule
                                CrossModuleCallbackUnit.RELAY,
                                CrossModuleCallbackUnit.SIGNAL ->
                                    manager.newModule(
                                        base.toNioPath().resolve("${unit.directory}.iml"),
                                        JavaModuleType.getModuleType().id,
                                    )
                            }
                        configure(native, directory, sdk)
                        UnitRoot(unit, native, directory)
                    }
                }
            val client = units.single { it.unit == CrossModuleCallbackUnit.CLIENT }.module
            units
                .filter { it.unit != CrossModuleCallbackUnit.CLIENT }
                .forEach {
                    ModuleRootModificationUtil.addDependency(client, it.module)
                }
            val relay = units.single { it.unit == CrossModuleCallbackUnit.RELAY }.module
            val signal = units.single { it.unit == CrossModuleCallbackUnit.SIGNAL }.module
            check(units.map { it.module }.distinct().size == 3) { "The fixture needs three distinct native modules" }
            check(ModuleRootManager.getInstance(client).dependencies.toSet().containsAll(listOf(relay, signal))) {
                "The client must depend on both callback endpoints"
            }
            check(signal !in ModuleRootManager.getInstance(relay).dependencies) {
                "The callback receiver must not depend on the supplied target"
            }
            val files =
                WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                    units.map { unit ->
                        unit.directory.createChildData(this, unit.unit.fileName).also { file ->
                            file.setBinaryContent(unit.unit.source(supply).toByteArray())
                        }
                    }
                }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).traceRefined()
            val compilation =
                WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    units.map {
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = it.module.name,
                            linkedBuildRoot = base.toNioPath(),
                            gradleProjectPath = ":${it.unit.directory}",
                            sourceSetName = "main",
                            sourceRoot = it.directory.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    },
                )
            val model =
                when (compilation) {
                    is WorkspaceSearchScopeModelCompilation.Compiled -> compilation.model
                    is WorkspaceSearchScopeModelCompilation.Rejected ->
                        error("Invalid authored module model: $compilation")
                }
            fixture = NativeScopedTraceFixture(project, root, model, files)
        }
        return fixture
    }

    private fun configure(module: Module, directory: VirtualFile, sdk: Sdk) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(directory).addSourceFolder(directory, false)
            val library = model.moduleLibraryTable.createLibrary("cross-module-stdlib")
            val roots = library.modifiableModel
            val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
            roots.commit()
        }
    }

    private data class UnitRoot(val unit: CrossModuleCallbackUnit, val module: Module, val directory: VirtualFile)
}

internal enum class CrossModuleCallbackSupply(val source: String) {
    DIRECT("package client\nfun direct() { signal.pulse() }\n"),
    ANONYMOUS("package client\nfun supply() { relay.forward { signal.pulse() } }\n"),
    NAMED("package client\nimport signal.pulse\nfun supply() { relay.forward(::pulse) }\n"),
}

internal enum class CrossModuleCallbackUnit(val directory: String, val fileName: String) {
    CLIENT("client", "Client.kt"),
    RELAY("relay", "Relay.kt"),
    SIGNAL("signal", "Signal.kt");

    fun source(supply: CrossModuleCallbackSupply): String =
        when (this) {
            CLIENT -> supply.source
            RELAY ->
                "package relay\nfun deliver(action: () -> Unit) { action() }\n" +
                    "fun forward(action: () -> Unit) { deliver(action) }\n"
            SIGNAL -> "package signal\nfun pulse() {}\n"
        }
}
