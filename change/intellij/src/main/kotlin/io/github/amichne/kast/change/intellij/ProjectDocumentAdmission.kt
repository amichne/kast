package io.github.amichne.kast.change.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Mutation admission cannot be blocked by an editor buffer outside the selected project. */
internal fun hasUnsavedProjectDocuments(project: Project): Boolean {
    val root = project.basePath?.let { Path.of(it) } ?: return true
    val documents = FileDocumentManager.getInstance()
    return documents.unsavedDocuments.any { document ->
        documents.getFile(document)?.path?.let { Path.of(it).normalize().startsWith(root) } == true
    }
}
