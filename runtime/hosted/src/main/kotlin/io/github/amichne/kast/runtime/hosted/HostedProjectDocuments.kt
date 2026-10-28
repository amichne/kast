package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
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

internal enum class ProjectDocumentPreparation {
    READY,
    UNSAVED_DOCUMENTS,
}

/** Save buffers and commit PSI after native refresh, before the next read epoch. */
internal fun prepareProjectDocuments(project: Project, root: CanonicalWorkspaceRoot): ProjectDocumentPreparation {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val outcome =
        if (!saveProjectDocuments(root)) ProjectDocumentPreparation.UNSAVED_DOCUMENTS
        else {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            ProjectDocumentPreparation.READY
        }
    Logger.getInstance(ProjectDocumentPreparation::class.java)
        .info("kast_readiness stage=DOCUMENT_PREPARATION outcome=${outcome.name}")
    return outcome
}
