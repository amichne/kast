package io.github.amichne.kast.shared.analysis

import com.intellij.psi.PsiFile

interface ReferenceIndexEnvironment {
    fun allFilePaths(): Collection<String>

    fun findPsiFile(filePath: String): PsiFile?

    fun <T> withReadAccess(action: () -> T): T

    /**
     * Runs [action] with exclusive access to the underlying analysis state.
     *
     * In the IntelliJ backend a read action is sufficient for the IDE threading model.
     * In the standalone backend this must serialize against all other read/write users
     * of the K2 analysis session, because the K2 FIR lazy declaration resolver is not
     * thread-safe for concurrent resolution within a single standalone session
     * (see commit 02c933a). Phase 2 reference scanning resolves declarations and
     * therefore must hold this lock instead of [withReadAccess].
     */
    fun <T> withExclusiveAccess(action: () -> T): T

    fun isCancelled(): Boolean
}
