package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.CoordinatedVfsRefreshAuthority
import io.github.amichne.kast.idea.transition.CompilerSourceRootAuthorities
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.IdeaCompilerVisibleSourceIdentityResolver
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceVfsObservationScope
import io.github.amichne.kast.indexer.gradle.bootstrap.readyInitialProjectModel
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@TestApplication
class WorkspaceTransitionRuntimeTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("refresh-scope")

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `refresh plan preserves discovery and avoids duplicate observed VFS refresh`() {
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfs,
            workspaceRefreshPlan(setOf(WorkspaceSignal.RecoveryProbe)),
        )
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfs,
            workspaceRefreshPlan(setOf(WorkspaceSignal.Source)),
        )
        assertEquals(
            WorkspaceRefreshPlan.ObservedVfs,
            workspaceRefreshPlan(setOf(WorkspaceSignal.Configuration)),
        )
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfs,
            workspaceRefreshPlan(setOf(WorkspaceSignal.InitialProjectModel)),
        )
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfsThenGradle,
            workspaceRefreshPlan(setOf(WorkspaceSignal.InitialProjectModel, WorkspaceSignal.BuildSemantic)),
        )
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfsThenGradle,
            workspaceRefreshPlan(setOf(WorkspaceSignal.RecoveryAudit)),
        )
        assertEquals(
            WorkspaceRefreshPlan.ObservedVfsThenGradle,
            workspaceRefreshPlan(setOf(WorkspaceSignal.BuildSemantic)),
        )
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfsThenGradle,
            workspaceRefreshPlan(
                setOf(WorkspaceSignal.RecoveryAudit, WorkspaceSignal.BuildSemantic),
            ),
        )
        assertEquals(
            WorkspaceRefreshPlan.GlobalVfsThenGradle,
            workspaceRefreshPlan(setOf(WorkspaceSignal.Source, WorkspaceSignal.BuildSemantic)),
        )
    }

    @Test
    fun `VFS refresh scope retains every external authority and removes nested scans`() {
        val buildRoot = tempDir.resolve("build-root").toAbsolutePath().normalize()
        val workspaceRoot = buildRoot.resolve("modules/app")
        val externalSource = tempDir.resolve("external-source")
        val externalClasspath = tempDir.resolve("artifacts/compiler-plugin.jar")
        val workspaceConfigDirectory = tempDir.resolve("workspace-state")
        val globalConfigDirectory = tempDir.resolve("global-state")
        val scope = WorkspaceVfsObservationScope(
            workspaceRoot = workspaceRoot,
            buildSemanticRoot = buildRoot,
            configurationFiles = setOf(
                workspaceConfigDirectory.resolve("config.toml"),
                globalConfigDirectory.resolve("config.toml"),
            ),
            compilerSourceRoots = { setOf(buildRoot.resolve("shared-source"), externalSource) },
            classpathRoots = { setOf(externalClasspath) },
        )

        assertEquals(
            setOf(buildRoot, externalSource, externalClasspath, workspaceConfigDirectory, globalConfigDirectory),
            WorkspaceVfsRefreshScope.from(scope).roots,
        )
    }

    @Test
    fun `configured unresolved source root remains a refresh authority`() {
        val externalSourceRoot = tempDir.resolve("external-configured-source").toAbsolutePath().normalize()
        val module = moduleFixture.get()
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.addContentEntry(VfsUtilCore.pathToUrl(externalSourceRoot.parent.toString()))
                        .addSourceFolder(VfsUtilCore.pathToUrl(externalSourceRoot.toString()), false)
                }
            }
        }

        assertTrue(Files.notExists(externalSourceRoot))
        assertFalse(externalSourceRoot in IdeaCompilerVisibleSourceIdentityResolver.sourceRoots(projectFixture.get()))
        assertTrue(externalSourceRoot in CompilerSourceRootAuthorities.from(projectFixture.get()).roots)
    }

    @Test
    fun `workspace VFS refresh applies a watcher-backed descendant change`() {
        val diskRoot = tempDir.resolve("watcher-backed-change")
        Files.createDirectories(diskRoot)
        val virtualRoot = checkNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(diskRoot),
        ) as NewVirtualFile
        assertTrue(virtualRoot.children.isEmpty())
        val created = diskRoot.resolve("NewSource.kt")
        Files.writeString(created, "class NewSource")
        virtualRoot.markDirty()
        assertNull(LocalFileSystem.getInstance().findFileByPathIfCached(created.toString()))
        val scope = WorkspaceVfsRefreshScope.from(
            WorkspaceVfsObservationScope(
                workspaceRoot = diskRoot,
                configurationFiles = emptySet(),
            ),
        )

        refreshWorkspaceVfs(CoordinatedVfsRefreshAuthority(), scope)

        assertNotNull(LocalFileSystem.getInstance().findFileByPathIfCached(created.toString()))
    }

    @Test
    fun `initial project model applies watcher dirty state before reconciliation`() {
        val diskRoot = tempDir.resolve("initial-watcher-change")
        Files.createDirectories(diskRoot)
        val virtualRoot = checkNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(diskRoot),
        ) as NewVirtualFile
        val created = diskRoot.resolve("BeforeObserver.kt")
        Files.writeString(created, "class BeforeObserver")
        virtualRoot.markDirty()
        assertNull(LocalFileSystem.getInstance().findFileByPathIfCached(created.toString()))
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, diskRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val buildInputs = BuildSemanticInputIdentity("stable-initial-build")
        val scope = WorkspaceVfsObservationScope(
            workspaceRoot = diskRoot,
            configurationFiles = emptySet(),
        )
        val refreshAuthority = CoordinatedVfsRefreshAuthority()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            initialProjectModelAuthority = readyInitialProjectModel(buildInputs),
            workspaceVfsObservationScope = scope,
            observeWorkspaceEvents = { _, _, _ -> AutoCloseable {} },
            refreshWorkspace = { _, _, signals ->
                assertEquals(setOf(WorkspaceSignal.InitialProjectModel), signals)
                refreshWorkspaceVfs(refreshAuthority, WorkspaceVfsRefreshScope.from(scope))
            },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = {
                assertNotNull(LocalFileSystem.getInstance().findFileByPathIfCached(created.toString()))
                WorkspaceStateIdentity("fresh-initial-state")
            },
            resolveBuildSemanticInputIdentity = { buildInputs },
        )

        try {
            indexing.start()
            indexing.awaitTermination()
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `buffered source event cannot bypass initial project model reconciliation`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, tempDir.resolve("buffered-source"))
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val refreshedSignals = CopyOnWriteArrayList<Set<WorkspaceSignal>>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            initialProjectModelAuthority = readyInitialProjectModel(BuildSemanticInputIdentity("stable")),
            observeWorkspaceEvents = { _, _, observer ->
                observer(WorkspaceSignal.Source)
                AutoCloseable {}
            },
            refreshWorkspace = { _, _, signals -> refreshedSignals += signals },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
            resolveBuildSemanticInputIdentity = { BuildSemanticInputIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(
                listOf(setOf(WorkspaceSignal.Source, WorkspaceSignal.InitialProjectModel)),
                refreshedSignals,
            )
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `initial build refresh targets the resolved Gradle root for a nested workspace`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val buildRoot = tempDir.resolve("nested-build").also { root ->
            root.toFile().mkdirs()
            root.resolve("settings.gradle.kts").toFile().writeText("rootProject.name = \"demo\"")
        }
        val workspaceRoot = buildRoot.resolve("modules/app").also { it.toFile().mkdirs() }
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val refreshedRoots = CopyOnWriteArrayList<Path>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            observeWorkspaceEvents = { _, _, _ -> AutoCloseable {} },
            refreshWorkspace = { _, refreshRoot, _ -> refreshedRoots.add(refreshRoot) },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(listOf(buildRoot.toRealPath()), refreshedRoots)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `event during refresh invalidates the production pass before publication`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("moving-refresh")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val observed = AtomicReference<(WorkspaceSignal) -> Unit>()
        val refreshPass = AtomicInteger()
        val publishedPasses = mutableListOf<Int>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            observeWorkspaceEvents = { _, _, observer ->
                observed.set(observer)
                AutoCloseable {}
            },
            refreshWorkspace = { _, _, _ ->
                if (refreshPass.incrementAndGet() == 1) {
                    observed.get().invoke(WorkspaceSignal.BuildSemantic)
                }
            },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(
                onCommit = { publishedPasses += refreshPass.get() },
            ),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(listOf(2), publishedPasses)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `cancellation during reconciliation cannot publish ready`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, tempDir.resolve("cancelled-reconciliation"))
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val admission = readyAdmission(project)
        val publications = mutableListOf<WorkspaceStateIdentity>()
        lateinit var indexing: KastIdeaProjectIndexing
        indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = admission,
            observeWorkspaceEvents = { _, _, _ -> AutoCloseable {} },
            refreshWorkspace = { _, _, _ -> },
            runProjectIndexing = { _, _ -> indexing.cancel() },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("cancelled-candidate") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertTrue(publications.isEmpty())
            assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    private fun readyAdmission(project: Project): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = project,
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    )
}
