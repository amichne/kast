package io.github.amichne.kast.idea.snapshot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

@TestApplication
class BuildClasspathFingerprintResolverTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
        private const val FIRST_ORDER_LIBRARY = "first-order-sensitive-dependency"
        private const val SECOND_ORDER_LIBRARY = "second-order-sensitive-dependency"
    }

    private val moduleFixture = projectFixture.moduleFixture("main")

    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `classpath artifact content affects fingerprint when its path is unchanged`() {
        val dependency = workspaceRoot.resolve("dependency.jar")
        writeJar(dependency, "first")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependency)
        val module = moduleFixture.get()
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                ModuleRootModificationUtil.addModuleLibrary(
                    module,
                    "content-sensitive-dependency",
                    listOf(VfsUtilCore.pathToUrl(dependency.toString()) + JarFileSystem.JAR_SEPARATOR),
                    emptyList<String>(),
                )
            }
        }
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot)
        val before = BuildClasspathFingerprintResolver.resolve(projectFixture.get(), workspaceIdentity)

        writeJar(dependency, "second-content")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependency)?.refresh(false, false)

        assertNotEquals(
            before,
            BuildClasspathFingerprintResolver.resolve(projectFixture.get(), workspaceIdentity),
        )
    }

    @Test
    fun `classpath dependency order affects fingerprint`() {
        val firstDependency = workspaceRoot.resolve("first-order-sensitive.jar")
        val secondDependency = workspaceRoot.resolve("second-order-sensitive.jar")
        writeJar(firstDependency, "first")
        writeJar(secondDependency, "second")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(firstDependency)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondDependency)
        val module = moduleFixture.get()
        addModuleLibrary(module, FIRST_ORDER_LIBRARY, firstDependency)
        addModuleLibrary(module, SECOND_ORDER_LIBRARY, secondDependency)
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot)
        val before = BuildClasspathFingerprintResolver.resolve(projectFixture.get(), workspaceIdentity)

        swapModuleLibraries(module, FIRST_ORDER_LIBRARY, SECOND_ORDER_LIBRARY)
        try {
            assertNotEquals(
                before,
                BuildClasspathFingerprintResolver.resolve(projectFixture.get(), workspaceIdentity),
            )
        } finally {
            swapModuleLibraries(module, FIRST_ORDER_LIBRARY, SECOND_ORDER_LIBRARY)
        }
    }

    @Test
    fun `content roots exclude derived module output and retain library artifacts`() {
        val contentRoot = workspaceRoot.resolve("module")
        val sourceRoot = contentRoot.resolve("src/main/kotlin")
        val moduleOutput = workspaceRoot.resolve("build/classes/kotlin/main")
        val dependency = workspaceRoot.resolve("dependency.jar")
        Files.createDirectories(sourceRoot.resolve("demo"))
        Files.writeString(sourceRoot.resolve("demo/Example.kt"), "package demo\nclass Example\n")
        Files.createDirectories(moduleOutput.resolve("demo"))
        Files.write(moduleOutput.resolve("demo/Generated.class"), byteArrayOf(1, 2, 3))
        writeJar(dependency, "library")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(moduleOutput)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependency)
        val moduleOutputUrl = VfsUtilCore.pathToUrl(moduleOutput.toString())
        val module = moduleFixture.get()
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.addContentEntry(VfsUtilCore.pathToUrl(contentRoot.toString()))
                        .addSourceFolder(VfsUtilCore.pathToUrl(sourceRoot.toString()), false)
                    model.getModuleExtension(CompilerModuleExtension::class.java)
                        .apply {
                            inheritCompilerOutputPath(false)
                            setCompilerOutputPath(moduleOutputUrl)
                        }
                }
                ModuleRootModificationUtil.addModuleLibrary(
                    module,
                    "retained-library-artifact",
                    listOf(VfsUtilCore.pathToUrl(dependency.toString()) + JarFileSystem.JAR_SEPARATOR),
                    emptyList<String>(),
                )
            }
        }

        val unfilteredRoots = ApplicationManager.getApplication().runReadAction<List<String>> {
            OrderEnumerator.orderEntries(projectFixture.get()).recursively().classes().urls.toList()
        }
        val unfilteredPaths = unfilteredRoots.mapTo(linkedSetOf()) { url ->
            Path.of(VfsUtilCore.urlToPath(url).substringBefore(JarFileSystem.JAR_SEPARATOR))
                .toAbsolutePath()
                .normalize()
        }
        val contentRoots = BuildClasspathFingerprintResolver.contentRoots(projectFixture.get())

        assertTrue(moduleOutput in unfilteredPaths, "Fixture roots: $unfilteredRoots")
        assertFalse(moduleOutput in contentRoots)
        assertTrue(dependency in contentRoots)
    }

    private fun addModuleLibrary(module: Module, name: String, path: Path) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                ModuleRootModificationUtil.addModuleLibrary(
                    module,
                    name,
                    listOf(VfsUtilCore.pathToUrl(path.toString()) + JarFileSystem.JAR_SEPARATOR),
                    emptyList<String>(),
                )
            }
        }
    }

    private fun swapModuleLibraries(
        module: Module,
        firstName: String,
        secondName: String,
    ) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                try {
                    val entries = model.orderEntries.toMutableList()
                    val firstIndex = entries.indexOfFirst { entry ->
                        entry is LibraryOrderEntry && entry.libraryName == firstName
                    }
                    val secondIndex = entries.indexOfFirst { entry ->
                        entry is LibraryOrderEntry && entry.libraryName == secondName
                    }
                    check(firstIndex >= 0 && secondIndex >= 0) {
                        "Expected both order-sensitive module libraries"
                    }
                    java.util.Collections.swap(entries, firstIndex, secondIndex)
                    model.rearrangeOrderEntries(entries.toTypedArray())
                    model.commit()
                } catch (failure: Throwable) {
                    if (!model.isDisposed) model.dispose()
                    throw failure
                }
            }
        }
    }

    private fun writeJar(path: Path, content: String) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(JarEntry("demo/Marker.class"))
            output.write(content.toByteArray())
            output.closeEntry()
        }
    }
}
