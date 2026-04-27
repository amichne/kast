package io.github.amichne.kast.standalone

import com.intellij.psi.PsiFile
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment

internal class StandaloneReferenceIndexEnvironment(
    private val session: StandaloneAnalysisSession,
    private val store: SqliteSourceIndexStore,
    private val cancelled: () -> Boolean,
) : ReferenceIndexEnvironment {
    override fun allFilePaths(): Collection<String> = store.loadManifest()?.keys.orEmpty()

    override fun findPsiFile(filePath: String): PsiFile? =
        runCatching { session.findKtFile(filePath) }.getOrNull()

    override fun <T> withReadAccess(action: () -> T): T = session.withReadAccess(action)

    // Standalone Phase 2 must hold the session write lock so K2 FIR resolution does
    // not run concurrently with foreground read operations on the same session.
    override fun <T> withExclusiveAccess(action: () -> T): T = session.withExclusiveAccess(action)

    override fun isCancelled(): Boolean = cancelled()
}
