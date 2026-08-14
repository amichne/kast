package io.github.amichne.kast.indexer.project.indexing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.amichne.kast.indexer.project.ProjectOpener
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
internal class KastWorkspaceDirectoryIndexExcludePolicyTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
        private val moduleFixture = projectFixture.moduleFixture("index-scope")
    }

    @Test
    fun `private plugin registers exact project indexing exclusions without consulting gitignore`() {
        val project = projectFixture.get()
        val workspaceRoot = Path.of(requireNotNull(project.basePath))
        Files.writeString(workspaceRoot.resolve(".gitignore"), "ignored-only-by-git/**\n")

        val policy = newPolicy(project, workspaceRoot)
        val actualUrls = policy.excludeUrlsForProject.toSet()
        val expectedNames = setOf(
            ".gradle",
            ".intellijPlatform",
            ".kast",
            ".run",
            ".venv",
            "site",
            "node_modules",
            "target",
        )
        val expectedUrls = expectedNames.mapTo(linkedSetOf()) { name ->
            VfsUtilCore.pathToUrl(workspaceRoot.resolve(name).toString())
        }
        val descriptor = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "Private indexer plugin descriptor is absent from the test runtime"
        }.bufferedReader().use { reader -> reader.readText() }

        assertFalse(descriptor.contains("<directoryIndexExcludePolicy"), descriptor)
        assertTrue(ProjectOpener.openProjectTask(workspaceRoot).beforeInit != null)
        assertEquals(expectedUrls, actualUrls)
        assertFalse(actualUrls.contains(VfsUtilCore.pathToUrl(workspaceRoot.resolve("ignored-only-by-git").toString())))
        assertFalse(actualUrls.contains(VfsUtilCore.pathToUrl(workspaceRoot.resolve("build").toString())))
    }

    @Test
    fun `model proven generated source remains visible below excluded rust target`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val workspaceRoot = Path.of(requireNotNull(project.basePath))
        val cargoRoot = workspaceRoot.resolve("native").also(Files::createDirectories)
        Files.writeString(cargoRoot.resolve("Cargo.toml"), "[package]\nname = \"native\"\nversion = \"0.1.0\"\n")
        val generatedRoot = cargoRoot.resolve("target/generated/kotlin").also(Files::createDirectories)
        val generatedFile = generatedRoot.resolve("Generated.kt").also {
            Files.writeString(it, "package generated\n\nclass Generated\n")
        }
        val unownedFile = cargoRoot.resolve("target/unowned.bin").also {
            Files.writeString(it, "not semantic evidence\n")
        }
        val localFileSystem = LocalFileSystem.getInstance()
        val workspaceVirtualRoot = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(workspaceRoot))
        val generatedVirtualRoot = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(generatedRoot))
        val generatedVirtualFile = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(generatedFile))
        val unownedVirtualFile = checkNotNull(localFileSystem.refreshAndFindFileByNioFile(unownedFile))
        val lifetime = Disposer.newDisposable("Kast directory exclusion policy fixture")
        var contentEntryAddedByTest = false

        try {
            val installation = KastWorkspaceDirectoryIndexExclusionAdmission.fromWorkspaceRoot(workspaceRoot)
                .install(project, lifetime)
            assertEquals(KastWorkspaceDirectoryIndexExclusionInstallation.Installed, installation)
            ModuleRootModificationUtil.updateModel(module) { model ->
                val contentEntry = model.contentEntries
                    .firstOrNull { entry -> entry.url == workspaceVirtualRoot.url }
                    ?: model.addContentEntry(workspaceVirtualRoot).also {
                        contentEntryAddedByTest = true
                    }
                contentEntry.addSourceFolder(generatedVirtualRoot, false)
            }

            val fileIndex = ProjectFileIndex.getInstance(project)
            val generatedIsSource = ApplicationManager.getApplication().runReadAction<Boolean> {
                fileIndex.isInSourceContent(generatedVirtualFile)
            }
            val unownedIsExcluded = ApplicationManager.getApplication().runReadAction<Boolean> {
                fileIndex.isExcluded(unownedVirtualFile)
            }

            assertTrue(generatedIsSource)
            assertTrue(unownedIsExcluded)
        } finally {
            Disposer.dispose(lifetime)
            ModuleRootModificationUtil.updateModel(module) { model ->
                val contentEntry = model.contentEntries
                    .firstOrNull { entry -> entry.url == workspaceVirtualRoot.url }
                    ?: return@updateModel
                if (contentEntryAddedByTest) {
                    model.removeContentEntry(contentEntry)
                } else {
                    contentEntry.sourceFolders
                        .firstOrNull { source -> source.url == generatedVirtualRoot.url }
                        ?.let(contentEntry::removeSourceFolder)
                }
            }
        }
    }

    private fun newPolicy(project: Project, workspaceRoot: Path): DirectoryIndexExcludePolicy =
        KastWorkspaceDirectoryIndexExcludePolicy(
            project,
            KastProjectWorkspaceRoot.fromWorkspaceRoot(workspaceRoot),
        )
}
