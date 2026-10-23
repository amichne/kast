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
import com.intellij.openapi.vfs.VfsUtil
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/** The photographed independent fixture, with real modules but an explicitly authored scope model. */
internal class NativeDecoderCallbackFixture(
    private val project: Project,
    private val base: VirtualFile,
    private val useModule: Module,
) {
    fun prepare(registerSdk: (Sdk) -> Unit): NativeScopedTraceFixture {
        lateinit var prepared: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            val sdk = JavaSdk.getInstance().createJdk("decoder-callback-jdk", System.getProperty("java.home"), false)
            registerSdk(sdk)
            val units = createUnits(sdk)
            connectUnits(units)
            val files =
                WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                    units.map { unit ->
                        val directory = VfsUtil.createDirectoryIfMissing(unit.root, "example/${unit.unit.directory}")
                        directory.createChildData(this, unit.unit.fileName).also {
                            it.setBinaryContent(unit.unit.source().toByteArray())
                        }
                    }
                }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).traceRefined()
            val boundaries = units.map { unit ->
                WorkspaceSourceRootBoundary(
                    ideaModuleName = unit.module.name,
                    linkedBuildRoot = base.toNioPath(),
                    gradleProjectPath = ":${unit.unit.directory}",
                    sourceSetName = "main",
                    sourceRoot = unit.root.toNioPath(),
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            }
            val model = WorkspaceSearchScopeModel.compile(root, ImportedWorkspaceModelState.COMPLETE, boundaries)
            check(model is WorkspaceSearchScopeModelCompilation.Compiled) { "Invalid fixture model: $model" }
            println("Decoder fixture authored model=$boundaries")
            DecoderFixtureModule.entries.forEach { unit ->
                val hash = MessageDigest.getInstance("SHA-256").digest(unit.source().toByteArray())
                val hex = hash.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
                println("Decoder fixture ${unit.relativePath} sha256=$hex")
            }
            prepared = NativeScopedTraceFixture(project = project, root = root, model = model.model, files = files)
        }
        return prepared
    }

    private fun connectUnits(units: List<UnitRoot>) {
        val use = units.single { it.unit == DecoderFixtureModule.USE }.module
        units
            .filter { it.unit != DecoderFixtureModule.USE }
            .forEach { unit ->
                ModuleRootModificationUtil.addDependency(use, unit.module)
            }
        val flow = units.single { it.unit == DecoderFixtureModule.FLOW }.module
        val contract = units.single { it.unit == DecoderFixtureModule.CONTRACT }.module
        check(ModuleRootManager.getInstance(use).dependencies.toSet() == setOf(flow, contract))
        check(ModuleRootManager.getInstance(flow).dependencies.isEmpty())
        check(ModuleRootManager.getInstance(contract).dependencies.isEmpty())
    }

    private fun createUnits(sdk: Sdk): List<UnitRoot> =
        WriteCommandAction.writeCommandAction(project).compute<List<UnitRoot>, RuntimeException> {
            DecoderFixtureModule.entries.map { unit ->
                val directory = base.createChildDirectory(this, unit.directory)
                val native =
                    if (unit == DecoderFixtureModule.USE) useModule
                    else
                        ModuleManager.getInstance(project)
                            .newModule(
                                base.toNioPath().resolve("${unit.directory}.iml"),
                                JavaModuleType.getModuleType().id,
                            )
                val source = VfsUtil.createDirectoryIfMissing(directory, "src/main/kotlin")
                ModuleRootModificationUtil.updateModel(native) { model ->
                    model.sdk = sdk
                    model.addContentEntry(directory).addSourceFolder(source, false)
                    val roots = model.moduleLibraryTable.createLibrary("decoder-stdlib").modifiableModel
                    val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                    roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                    roots.commit()
                }
                UnitRoot(unit, native, source)
            }
        }

    private data class UnitRoot(val unit: DecoderFixtureModule, val module: Module, val root: VirtualFile)
}

internal enum class DecoderFixtureModule(val directory: String, val fileName: String) {
    CONTRACT("contract", "Decoder.kt"),
    FLOW("flow", "Flow.kt"),
    USE("use", "Examples.kt");

    val relativePath = "$directory/src/main/kotlin/example/$directory/$fileName"

    fun source(): String = Files.readString(fixtureRoot.resolve(relativePath))

    companion object {
        val fixtureRoot: Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("runtime/hosted")) }
                .resolve("experiments/host-observation/nested-callback-fixture")
    }
}
