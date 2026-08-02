package io.github.amichne.kast.shared.analysis

import com.intellij.psi.PsiFile

interface ReferenceIndexEnvironment {
    fun findPsiFile(filePath: String): PsiFile?

    fun <T> withReadAccess(action: () -> T): T

    fun <T> withPsiFileReadAccess(
        filePath: String,
        action: (PsiFile) -> T,
    ): T? = withReadAccess {
        findPsiFile(filePath)
            ?.takeIf { psiFile -> psiFile.isValid }
            ?.let(action)
    }

    fun <T> withPsiFileExclusiveAccess(
        filePath: String,
        action: (PsiFile) -> T,
    ): T? = withExclusiveAccess {
        findPsiFile(filePath)
            ?.takeIf { psiFile -> psiFile.isValid }
            ?.let(action)
    }

    /**
     * Runs [action] with exclusive access to the underlying analysis state.
     *
     * Implementations may cancel and retry [action] to yield to higher-priority IDE work,
     * so callers should keep side effects inside the returned value.
     *
     * A non-blocking read action is sufficient for ordinary platform work. This
     * exclusive path must serialize against all other read/write users
     * of the K2 analysis session, because the K2 FIR lazy declaration resolver is not
     * thread-safe for concurrent resolution within a single indexer session
     * (see commit 02c933a). Symbol relationship indexing resolves declarations and
     * therefore must hold this lock instead of [withReadAccess].
     */
    fun <T> withExclusiveAccess(action: () -> T): T

    fun isCancelled(): Boolean
}
