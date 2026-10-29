package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.runInEdtAndWait
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path

class NativeProjectDocumentsTest : NativeSmartModeFixtureTest() {
    fun testPreparationSavesAndCommitsOwnedDocument() {
        val directory = Files.createDirectories(Path.of(checkNotNull(project.basePath)))
        val path = directory.resolve("Preparation.kt")
        Files.writeString(path, "class Before\n")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(directory.toRealPath()) as Refinement.Refined).value
        runInEdtAndWait {
            val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val psi = checkNotNull(PsiManager.getInstance(project).findFile(file))
            val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
            val manager = PsiDocumentManager.getInstance(project)
            assertEquals("class Before\n", psi.text)
            WriteCommandAction.runWriteCommandAction(project) { document.setText("class After\n") }
            assertFalse(manager.isCommitted(document))
            assertEquals(ProjectDocumentPreparation.READY, prepareProjectDocuments(project, root))
            assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            assertTrue("Preparation must synchronize PSI before read admission", manager.isCommitted(document))
            assertEquals("class After\n", psi.text)
        }
        assertEquals("class After\n", Files.readString(path))
    }

    fun testNativeSaveVetoPreservesTheBufferAndDisk() {
        val directory = Files.createDirectories(Path.of(checkNotNull(project.basePath)))
        val path = directory.resolve("Veto.kt")
        Files.writeString(path, "class Before\n")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(directory.toRealPath()) as Refinement.Refined).value
        runInEdtAndWait {
            val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
            val veto =
                object : FileDocumentSynchronizationVetoer() {
                    override fun maySaveDocument(candidate: Document, isSaveExplicit: Boolean) = candidate !== document
                }
            ExtensionTestUtil.maskExtensions(
                FileDocumentSynchronizationVetoer.EP_NAME,
                FileDocumentSynchronizationVetoer.EP_NAME.extensionList + veto,
                testRootDisposable,
            )
            WriteCommandAction.runWriteCommandAction(project) { document.setText("class After\n") }
            assertEquals(ProjectDocumentPreparation.UNSAVED_DOCUMENTS, prepareProjectDocuments(project, root))
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            assertEquals("class After\n", document.text)
            assertEquals("class Before\n", Files.readString(path))
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
    }

    fun testPreparationDoesNotSaveOutsideTheSelectedRoot() {
        val directory = Files.createDirectories(Path.of(checkNotNull(project.basePath)))
        val selected = Files.createDirectories(directory.resolve("selected"))
        val path = directory.resolve("Unrelated.kt")
        Files.writeString(path, "class Before\n")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(selected.toRealPath()) as Refinement.Refined).value
        runInEdtAndWait {
            val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
            WriteCommandAction.runWriteCommandAction(project) { document.setText("class After\n") }
            assertEquals(ProjectDocumentPreparation.READY, prepareProjectDocuments(project, root))
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            assertEquals("class After\n", document.text)
            assertEquals("class Before\n", Files.readString(path))
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
    }
}
