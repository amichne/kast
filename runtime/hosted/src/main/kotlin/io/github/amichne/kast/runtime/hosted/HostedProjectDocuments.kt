package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path

/** Save only editor buffers owned by the selected root; never touch another project. */
internal fun saveProjectDocuments(root: CanonicalWorkspaceRoot): Boolean {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val documents = FileDocumentManager.getInstance()
    val rootPath = Path.of(root.value)
    val owned =
        documents.unsavedDocuments.filter { document ->
            documents.getFile(document)?.path?.let { Path.of(it).normalize().startsWith(rootPath) } == true
        }
    owned.forEach(documents::saveDocument)
    return owned.none(documents::isDocumentUnsaved)
}
