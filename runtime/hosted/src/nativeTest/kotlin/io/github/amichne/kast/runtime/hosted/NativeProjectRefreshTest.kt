package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
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
}
