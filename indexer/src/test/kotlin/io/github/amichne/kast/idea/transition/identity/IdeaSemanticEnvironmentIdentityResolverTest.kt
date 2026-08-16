package io.github.amichne.kast.idea.transition

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.idea.waitUntilIndexesAreReady
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JsCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class IdeaSemanticEnvironmentIdentityResolverTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
        private const val EXPORTED_LIBRARY_NAME = "export-sensitive-dependency"
    }

    private val moduleFixture = projectFixture.moduleFixture("main")

    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `additional compiler settings affect semantic environment identity`() {
        moduleFixture.get()
        val project = projectFixture.get()
        val holder = KotlinCompilerSettings.getInstance(project)
        val original = holder.settings.additionalArguments
        val before = resolve(project)

        try {
            holder.update {
                additionalArguments = distinctValue(original, "-Xcontext-parameters")
            }

            assertNotEquals(before, resolve(project))
        } finally {
            holder.update { additionalArguments = original }
        }
    }

    @Test
    fun `JVM compiler arguments affect semantic environment identity`() {
        moduleFixture.get()
        val project = projectFixture.get()
        val holder = Kotlin2JvmCompilerArgumentsHolder.getInstance(project)
        val original = holder.settings.jvmTarget
        val before = resolve(project)

        try {
            holder.update {
                jvmTarget = distinctValue(original, "21")
            }

            assertNotEquals(before, resolve(project))
        } finally {
            holder.update { jvmTarget = original }
        }
    }

    @Test
    fun `JS compiler arguments affect semantic environment identity`() {
        moduleFixture.get()
        val project = projectFixture.get()
        val holder = Kotlin2JsCompilerArgumentsHolder.getInstance(project)
        val original = holder.settings.moduleKind
        val changed = if (original == "commonjs") "umd" else "commonjs"
        val before = resolve(project)

        try {
            holder.update { moduleKind = changed }

            assertNotEquals(before, resolve(project))
        } finally {
            holder.update { moduleKind = original }
        }
    }

    @Test
    fun `exported dependency affects semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val classesRoot = workspaceRoot.resolve("dependency-classes")
        Files.createDirectories(classesRoot.resolve("demo"))
        Files.write(classesRoot.resolve("demo/Marker.class"), byteArrayOf(1, 2, 3))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classesRoot)
        addModuleLibrary(module, EXPORTED_LIBRARY_NAME, classesRoot)
        val before = resolve(project)

        setLibraryExported(module, EXPORTED_LIBRARY_NAME, exported = true)
        try {
            assertNotEquals(before, resolve(project))
        } finally {
            setLibraryExported(module, EXPORTED_LIBRARY_NAME, exported = false)
        }
    }

    @Test
    fun `Java source content affects semantic environment identity`() {
        val project = projectFixture.get()
        val source = compilerSource(
            project = project,
            sourceRoot = workspaceRoot.resolve("src/main/java"),
            relativePath = "demo/JavaDependency.java",
            content = "package demo; public final class JavaDependency {}",
        )
        val before = resolve(project)

        replaceContent(source, "package demo; public final class JavaDependency { public static int value() { return 1; } }")

        assertNotEquals(before, resolve(project))
    }

    @Test
    fun `generated Kotlin source content affects semantic environment identity`() {
        assertFalse(
            SourceIndexFilePolicy.isEligibleWorkspaceRelative("build/generated/ksp/main/kotlin/demo/Generated.kt"),
            "fixture must remain outside the persisted Kotlin source inventory",
        )
        val project = projectFixture.get()
        val source = compilerSource(
            project = project,
            sourceRoot = workspaceRoot.resolve("build/generated/ksp/main/kotlin"),
            relativePath = "demo/Generated.kt",
            content = "package demo\n\nclass Generated",
        )
        val before = resolve(project)

        replaceContent(source, "package demo\n\nclass Generated(val value: Int)")

        assertNotEquals(before, resolve(project))
    }

    @Test
    fun `compiler plugin artifact content affects identity when its path is unchanged`() {
        moduleFixture.get()
        val project = projectFixture.get()
        val plugin = workspaceRoot.resolve("compiler-plugin.jar")
        Files.writeString(plugin, "first")
        val holder = KotlinCommonCompilerArgumentsHolder.getInstance(project)
        val original = holder.settings.pluginClasspaths
        try {
            holder.update { pluginClasspaths = arrayOf(plugin.toString()) }
            val before = resolve(project)

            Files.writeString(plugin, "second")

            assertNotEquals(before, resolve(project))
        } finally {
            holder.update { pluginClasspaths = original }
        }
    }

    @Test
    fun `external compiler visible source content affects identity`() {
        val project = projectFixture.get()
        val exactRoot = workspaceRoot.resolve("exact").also(Files::createDirectories)
        val source = compilerSource(
            project = project,
            sourceRoot = workspaceRoot.resolve("shared/src/main/java"),
            relativePath = "demo/Shared.java",
            content = "package demo; public final class Shared {}",
        )
        val before = resolve(project, exactRoot)

        replaceContent(source, "package demo; public final class Shared { public static int value() { return 1; } }")

        assertNotEquals(before, resolve(project, exactRoot))
    }

    @Test
    fun `SDK presentation name does not affect semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val rootModel = ModuleRootManager.getInstance(module)
        val originalSdk = rootModel.sdk
        val originalSdkInherited = rootModel.isSdkInherited
        val sdkTable = ProjectJdkTable.getInstance(project)
        val classesRoot = workspaceRoot.resolve("jdk/classes").also(Files::createDirectories)
        val virtualClassesRoot = checkNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classesRoot),
        )
        val sdk = sdkTable.createSdk(
            "temurin-21",
            sdkTable.defaultSdkType,
        )
        val renamedSdk = sdkTable.createSdk(
            "temurin-21 (2)",
            sdkTable.defaultSdkType,
        )

        try {
            listOf(sdk, renamedSdk).forEach { candidate ->
                updateSdk(candidate) {
                    homePath = workspaceRoot.resolve("jdk").toString()
                    versionString = "21.0.7"
                    addRoot(virtualClassesRoot, OrderRootType.CLASSES)
                }
            }
            ApplicationManager.getApplication().runWriteAction {
                sdkTable.addJdk(sdk)
                sdkTable.addJdk(renamedSdk)
            }
            ModuleRootModificationUtil.setModuleSdk(module, sdk)
            assertEquals("temurin-21", ModuleRootManager.getInstance(module).sdk?.name)
            val before = resolve(project)

            ModuleRootModificationUtil.setModuleSdk(module, renamedSdk)
            assertEquals("temurin-21 (2)", ModuleRootManager.getInstance(module).sdk?.name)

            assertEquals(before, resolve(project))
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                if (originalSdkInherited) {
                    model.inheritSdk()
                } else {
                    model.sdk = originalSdk
                }
            }
            ApplicationManager.getApplication().runWriteAction {
                sdkTable.removeJdk(sdk)
                sdkTable.removeJdk(renamedSdk)
            }
        }
    }

    private fun resolve(project: Project, exactRoot: Path = workspaceRoot): String =
        IdeaSemanticEnvironmentIdentityResolver.resolve(
        project = project,
        workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(exactRoot),
    )

    private fun distinctValue(current: String?, candidate: String): String =
        if (current == candidate) "$candidate-different" else candidate

    private fun compilerSource(
        project: Project,
        sourceRoot: Path,
        relativePath: String,
        content: String,
    ): VirtualFile {
        val path = sourceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        val localFileSystem = LocalFileSystem.getInstance()
        val contentRoot = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(workspaceRoot))
        val virtualSourceRoot = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(sourceRoot))
        val source = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(path))
        ModuleRootModificationUtil.updateModel(moduleFixture.get()) { model ->
            model.addContentEntry(contentRoot).addSourceFolder(virtualSourceRoot, false)
        }
        waitUntilIndexesAreReady(project)
        return source
    }

    private fun replaceContent(file: VirtualFile, content: String) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                VfsUtil.saveText(file, content)
            }
        }
    }

    private fun updateSdk(
        sdk: com.intellij.openapi.projectRoots.Sdk,
        update: com.intellij.openapi.projectRoots.SdkModificator.() -> Unit,
    ) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                sdk.sdkModificator.apply(update).commitChanges()
            }
        }
    }

    private fun addModuleLibrary(module: Module, name: String, classesRoot: Path) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                ModuleRootModificationUtil.addModuleLibrary(
                    module,
                    name,
                    listOf(VfsUtilCore.pathToUrl(classesRoot.toString())),
                    emptyList<String>(),
                )
            }
        }
    }

    private fun setLibraryExported(module: Module, name: String, exported: Boolean) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                try {
                    val entry = model.orderEntries
                        .filterIsInstance<LibraryOrderEntry>()
                        .single { orderEntry -> orderEntry.libraryName == name }
                    entry.isExported = exported
                    model.commit()
                } catch (failure: Throwable) {
                    if (!model.isDisposed) model.dispose()
                    throw failure
                }
            }
        }
    }
}
