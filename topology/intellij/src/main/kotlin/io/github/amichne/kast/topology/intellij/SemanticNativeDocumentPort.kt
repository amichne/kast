package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call

internal enum class SemanticNativeDocumentState {
    CLEAN,
    DIRTY,
    UNCOMMITTED,
}

/** Only document observations are supplied here; the capture owns rejection and effect order. */
internal fun interface SemanticNativeDocumentPort {
    fun observe(file: VirtualFile): SemanticNativeDocumentState

    class Native(private val project: Project, private val observation: IntellijReadObservation) :
        SemanticNativeDocumentPort {
        override fun observe(file: VirtualFile): SemanticNativeDocumentState {
            val documents = FileDocumentManager.getInstance()
            if (observation.call(IntellijReadCall.DOCUMENT_DIRTY_CHECK) { documents.isFileModified(file) })
                return SemanticNativeDocumentState.DIRTY
            val document =
                observation.call(IntellijReadCall.DOCUMENT_CACHE_LOOKUP) { documents.getCachedDocument(file) }
            if (
                document != null &&
                    !observation.call(IntellijReadCall.DOCUMENT_COMMIT_CHECK) {
                        PsiDocumentManager.getInstance(project).isCommitted(document)
                    }
            )
                return SemanticNativeDocumentState.UNCOMMITTED
            return SemanticNativeDocumentState.CLEAN
        }
    }
}
