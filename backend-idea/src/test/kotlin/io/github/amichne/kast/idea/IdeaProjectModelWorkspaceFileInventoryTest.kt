package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class IdeaProjectModelWorkspaceFileInventoryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `injected project model preserves scripts exact owners and workspace containment`() {
        val includedRoot = workspaceRoot.resolve("included")
        val rootSettings = file(workspaceRoot.resolve("settings.gradle.kts"))
        val rootBuild = file(workspaceRoot.resolve("build.gradle.kts"))
        val includedSettings = file(includedRoot.resolve("settings.gradle.kts"))
        val includedBuild = file(includedRoot.resolve("build.gradle.kts"))
        val conventionScript = file(workspaceRoot.resolve("build-logic/src/main/kotlin/conventions.gradle.kts"))
        val ordinaryScript = file(workspaceRoot.resolve("app/scripts/check.main.kts"))
        val rootSource = file(workspaceRoot.resolve("app/src/main/kotlin/App.kt"))
        val includedSource = file(includedRoot.resolve("app/src/main/kotlin/Included.kt"))
        val sharedSource = file(workspaceRoot.resolve("shared/Shared.kt"))
        val outsideSource = workspaceRoot.parent.resolve("outside-workspace/Outside.kt").toAbsolutePath().normalize()
        val model = IdeaWorkspaceFileProjectModel(
            modules = listOf(
                module(
                    name = "root",
                    contentRoot = workspaceRoot,
                    ownedFiles = listOf(rootSource, conventionScript, ordinaryScript, sharedSource, outsideSource),
                ),
                module(
                    name = "included",
                    contentRoot = includedRoot,
                    ownedFiles = listOf(includedSource, sharedSource),
                ),
            ),
            linkedBuildRoots = listOf(workspaceRoot, includedRoot),
            moduleAssociations = listOf(
                rootAssociation("root", workspaceRoot),
                rootAssociation("included", includedRoot),
            ),
            rootGradleScriptPaths = setOf(rootSettings, rootBuild, includedSettings, includedBuild),
        )
        val inventory = inventory(FakeProjectModelAccess(model = model))

        val source = inventory.snapshot(WorkspaceFileKindDomain.SOURCE_ONLY)
        val script = inventory.snapshot(WorkspaceFileKindDomain.SCRIPT_ONLY)
        val mixed = inventory.snapshot(WorkspaceFileKindDomain.MIXED)

        assertEquals(
            listOf(rootSource, sharedSource).map(Path::toString).sorted(),
            source.module(moduleIdentity("root")).filePaths(WorkspaceFileKindDomain.SOURCE_ONLY),
        )
        assertEquals(
            listOf(includedSource, sharedSource).map(Path::toString).sorted(),
            source.module(moduleIdentity("included")).filePaths(WorkspaceFileKindDomain.SOURCE_ONLY),
        )
        assertEquals(
            listOf(rootSettings, rootBuild, conventionScript, ordinaryScript).map(Path::toString).sorted(),
            script.module(moduleIdentity("root")).filePaths(WorkspaceFileKindDomain.SCRIPT_ONLY),
        )
        assertEquals(
            listOf(includedSettings, includedBuild).map(Path::toString).sorted(),
            script.module(moduleIdentity("included")).filePaths(WorkspaceFileKindDomain.SCRIPT_ONLY),
        )
        assertEquals(
            (source.module(moduleIdentity("root")).filePaths(WorkspaceFileKindDomain.SOURCE_ONLY) +
                script.module(moduleIdentity("root")).filePaths(WorkspaceFileKindDomain.SCRIPT_ONLY)).sorted(),
            mixed.module(moduleIdentity("root")).filePaths(WorkspaceFileKindDomain.MIXED),
        )
        assertEquals(
            listOf("included", "root"),
            source.modules
                .filter { module -> sharedSource.toString() in module.filePaths(WorkspaceFileKindDomain.SOURCE_ONLY) }
                .map { module -> module.identity.value },
        )
        source.modules.forEach { module ->
            assertFalse(outsideSource.toString() in module.filePaths(WorkspaceFileKindDomain.SOURCE_ONLY))
        }
    }

    @Test
    fun `indexing project model failure is typed before provider read`() {
        val access = FakeProjectModelAccess(
            model = emptyModel(),
            isIndexing = true,
        )

        val failure = assertThrows(WorkspaceProjectModelIncompleteException::class.java) {
            inventory(access).snapshot(WorkspaceFileKindDomain.MIXED)
        }

        assertEquals(WorkspaceProjectModelIncompleteReason.RUNTIME_INDEXING, failure.reason)
        assertEquals(0, access.readCount)
    }

    @Test
    fun `bridge provider failure is mapped to project model unavailable`() {
        val access = FakeProjectModelAccess(
            failure = IllegalStateException("bridge unavailable"),
        )

        val failure = assertThrows(WorkspaceProjectModelIncompleteException::class.java) {
            inventory(access).snapshot(WorkspaceFileKindDomain.MIXED)
        }

        assertEquals(WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE, failure.reason)
        assertEquals(1, access.readCount)
    }

    @Test
    fun `IDEA cancellation escapes project model failure mapping`() {
        val cancellation = ProcessCanceledException()
        val access = FakeProjectModelAccess(failure = cancellation)

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            inventory(access).snapshot(WorkspaceFileKindDomain.MIXED)
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `task cancellation escapes project model failure mapping`() {
        val cancellation = CancellationException("cancelled")
        val access = FakeProjectModelAccess(failure = cancellation)

        val thrown = assertThrows(CancellationException::class.java) {
            inventory(access).snapshot(WorkspaceFileKindDomain.MIXED)
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `linked Gradle root without exact root module association is typed`() {
        file(workspaceRoot.resolve("settings.gradle.kts"))
        val model = IdeaWorkspaceFileProjectModel(
            modules = listOf(module("app", workspaceRoot, emptyList())),
            linkedBuildRoots = listOf(workspaceRoot),
            moduleAssociations = emptyList(),
            rootGradleScriptPaths = emptySet(),
        )

        val failure = assertThrows(WorkspaceProjectModelIncompleteException::class.java) {
            inventory(FakeProjectModelAccess(model)).snapshot(WorkspaceFileKindDomain.MIXED)
        }

        assertEquals(WorkspaceProjectModelIncompleteReason.LINKED_ROOT_UNASSOCIATED, failure.reason)
    }

    private fun inventory(access: IdeaWorkspaceFileProjectModelAccess): IdeaProjectModelWorkspaceFileInventory =
        IdeaProjectModelWorkspaceFileInventory(
            workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
            projectModelAccess = access,
        )

    private fun emptyModel(): IdeaWorkspaceFileProjectModel = IdeaWorkspaceFileProjectModel(
        modules = emptyList(),
        linkedBuildRoots = emptyList(),
        moduleAssociations = emptyList(),
        rootGradleScriptPaths = emptySet(),
    )

    private fun module(
        name: String,
        contentRoot: Path,
        ownedFiles: List<Path>,
    ): IdeaWorkspaceFileProjectModel.Module = IdeaWorkspaceFileProjectModel.Module(
        identity = moduleIdentity(name),
        sourceRoots = listOf(contentRoot.resolve("src/main/kotlin")),
        contentRoots = listOf(contentRoot),
        dependencyModuleNames = emptyList(),
        ownedFilePaths = ownedFiles,
    )

    private fun rootAssociation(
        moduleName: String,
        linkedBuildRoot: Path,
    ): IdeaWorkspaceFileProjectModel.GradleModuleAssociation =
        IdeaWorkspaceFileProjectModel.GradleModuleAssociation(
            moduleIdentity = moduleIdentity(moduleName),
            linkedBuildRoot = linkedBuildRoot,
            rootModule = true,
        )

    private fun moduleIdentity(value: String): IdeaWorkspaceModuleIdentity =
        IdeaWorkspaceModuleIdentity.of(value)

    private fun file(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, "// fixture").toAbsolutePath().normalize()
    }

    private class FakeProjectModelAccess(
        private val model: IdeaWorkspaceFileProjectModel? = null,
        override val isIndexing: Boolean = false,
        private val failure: RuntimeException? = null,
    ) : IdeaWorkspaceFileProjectModelAccess {
        var readCount: Int = 0
            private set

        override fun read(): IdeaWorkspaceFileProjectModel {
            readCount += 1
            failure?.let { throw it }
            return checkNotNull(model)
        }
    }
}
