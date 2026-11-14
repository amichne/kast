package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.runInEdtAndWait
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.hosted.workspace.IntellijWorkspaceRefreshPort
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshEffectResult
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshModelBoundary
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NativeProjectRefreshTest : NativeSmartModeFixtureTest() {
    fun testExternalWriteReachesDocumentAndPsiBeforeRefreshCompletes() = runBlocking {
        val directory = Files.createDirectories(Path.of(checkNotNull(project.basePath)))
        val path = directory.resolve("External.kt")
        Files.writeString(path, "class Before\n")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(directory.toRealPath()) as Refinement.Refined).value
        val owner = SupervisorJob(coroutineContext[Job])
        val query = HostedQueryService(project, CoroutineScope(coroutineContext + owner))
        try {
            runInEdtAndWait {
                val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                val psi = checkNotNull(PsiManager.getInstance(project).findFile(file))
                val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
                assertEquals("class Before\n", psi.text)
                assertEquals("class Before\n", document.text)
            }
            Files.writeString(path, "class FromExternalWriter\n")
            // Model a delivered watcher notification, independent of native watcher timing.
            runInEdtAndWait {
                VfsUtil.markDirty(false, false, checkNotNull(LocalFileSystem.getInstance().findFileByNioFile(path)))
            }
            val port =
                IntellijWorkspaceRefreshPort(
                    project = project,
                    root = root,
                    query = query,
                    modelInputs = WorkspaceRefreshModelBoundary(HostedGradleRevision()),
                )
            val completed = CompletableDeferred<WorkspaceRefreshEffectResult>()
            port.startIncremental { outcome ->
                ReadAction.run<RuntimeException> {
                    val file = checkNotNull(LocalFileSystem.getInstance().findFileByNioFile(path))
                    val document = checkNotNull(FileDocumentManager.getInstance().getCachedDocument(file))
                    assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
                    assertTrue(PsiDocumentManager.getInstance(project).isCommitted(document))
                    assertEquals("class FromExternalWriter\n", document.text)
                    assertEquals(
                        "class FromExternalWriter\n",
                        checkNotNull(PsiManager.getInstance(project).findFile(file)).text,
                    )
                }
                completed.complete(outcome)
            }
            assertEquals(WorkspaceRefreshEffectResult.SUCCEEDED, withTimeout(15.seconds) { completed.await() })
            assertEquals("class FromExternalWriter\n", Files.readString(path))
        } finally {
            Disposer.dispose(query)
            owner.cancelAndJoin()
        }
    }

    fun testImportedScopeDeliversReadInputEditsAndSkipsUnownedTrees() = runBlocking {
        val fixture = importedScopeFixture()
        val owner = SupervisorJob(coroutineContext[Job])
        val query = HostedQueryService(project, CoroutineScope(coroutineContext + owner))
        try {
            val delivered = recordVfsEvents()
            fixture.editExternally()
            val port =
                IntellijWorkspaceRefreshPort(
                    project = project,
                    root = fixture.root,
                    query = query,
                    modelInputs = WorkspaceRefreshModelBoundary(HostedGradleRevision()),
                )
            val completed = CompletableDeferred<WorkspaceRefreshEffectResult>()
            port.startIncremental { outcome ->
                ReadAction.run<RuntimeException> {
                    val file = checkNotNull(LocalFileSystem.getInstance().findFileByNioFile(fixture.edited))
                    assertEquals(
                        "class AfterExternalEdit\n",
                        checkNotNull(PsiManager.getInstance(project).findFile(file)).text,
                    )
                }
                completed.complete(outcome)
            }
            assertEquals(WorkspaceRefreshEffectResult.SUCCEEDED, withTimeout(15.seconds) { completed.await() })
            val observed = delivered.toSet()
            assertTrue(observed.toString(), "VFileContentChangeEvent ${fixture.edited}" in observed)
            assertTrue(observed.toString(), "VFileCreateEvent ${fixture.sources.resolve("Added.kt")}" in observed)
            assertTrue(observed.toString(), "VFileContentChangeEvent ${fixture.build}" in observed)
            assertTrue(observed.toString(), "VFileCreateEvent ${fixture.base.resolve("src/test")}" in observed)
            assertTrue(observed.toString(), observed.none { it.contains("${fixture.base.resolve("scratch")}/") })

            // Control: the unowned edit is real, and a whole-root refresh still observes it.
            runInEdtAndWait {
                val base = LocalFileSystem.getInstance().findFileByNioFile(fixture.base)
                VfsUtil.markDirtyAndRefresh(false, true, true, base)
            }
            assertTrue(delivered.toString(), "VFileContentChangeEvent ${fixture.scratch}" in delivered)
        } finally {
            Disposer.dispose(query)
            owner.cancelAndJoin()
        }
    }

    private class ImportedScopeFixture(val base: Path, val root: CanonicalWorkspaceRoot) {
        val sources: Path = base.resolve("src/main/kotlin")
        val edited: Path = sources.resolve("Edited.kt")
        val build: Path = base.resolve("build.gradle.kts")
        val scratch: Path = base.resolve("scratch/checkout/src/main/kotlin/Scratch.kt")

        fun editExternally() {
            Files.writeString(edited, "class AfterExternalEdit\n")
            Files.writeString(sources.resolve("Added.kt"), "class Added\n")
            Files.writeString(build, "// after an external edit\n")
            Files.writeString(scratch, "class ScratchAfterExternalEdit\n")
            Files.writeString(Files.createDirectories(base.resolve("src/test/kotlin")).resolve("AddedTest.kt"), "\n")
        }
    }

    /** One imported source root, one declared missing test root, and an unowned nested checkout loaded in VFS. */
    private fun importedScopeFixture(): ImportedScopeFixture {
        val base = Files.createDirectories(Path.of(checkNotNull(project.basePath))).toRealPath()
        val fixture =
            ImportedScopeFixture(base, (CanonicalWorkspaceRoot.fromCanonicalPath(base) as Refinement.Refined).value)
        Files.writeString(Files.createDirectories(fixture.sources).resolve("Edited.kt"), "class Before\n")
        Files.writeString(fixture.build, "// before\n")
        Files.createDirectories(fixture.scratch.parent)
        Files.writeString(fixture.scratch, "class ScratchBefore\n")
        runInEdtAndWait {
            val files = LocalFileSystem.getInstance()
            val baseFile = checkNotNull(files.refreshAndFindFileByNioFile(base))
            // List every tree, including the unowned one, as a completed IDE scan would. Refresh rescans only
            // directories whose children are loaded, so this keeps the case independent of background indexing.
            VfsUtilCore.visitChildrenRecursively(
                baseFile,
                object : VirtualFileVisitor<Unit>() {},
            )
            ModuleRootModificationUtil.updateModel(module) { model ->
                val content = model.addContentEntry(baseFile)
                content.addSourceFolder(checkNotNull(files.findFileByNioFile(fixture.sources)), false)
                content.addSourceFolder(VfsUtilCore.pathToUrl(base.resolve("src/test/kotlin").toString()), true)
            }
            val index = ProjectFileIndex.getInstance(project)
            val editedFile = checkNotNull(files.findFileByNioFile(fixture.edited))
            assertTrue(index.isInSourceContent(editedFile))
            assertFalse(index.isInSourceContent(checkNotNull(files.findFileByNioFile(fixture.scratch))))
            assertEquals("class Before\n", checkNotNull(PsiManager.getInstance(project).findFile(editedFile)).text)
        }
        return fixture
    }

    private fun recordVfsEvents(): ConcurrentLinkedQueue<String> {
        val delivered = ConcurrentLinkedQueue<String>()
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        events.forEach { delivered += "${it.javaClass.simpleName} ${it.path}" }
                    }
                },
            )
        return delivered
    }
}
