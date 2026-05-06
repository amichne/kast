package io.github.amichne.kast.standalone

import com.intellij.psi.PsiFile
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment
import java.util.concurrent.CancellationException

internal class StandaloneReferenceIndexEnvironment(
    private val session: StandaloneAnalysisSession,
    private val store: SqliteSourceIndexStore,
    private val cancelled: () -> Boolean,
) : ReferenceIndexEnvironment {
    override fun allFilePaths(): Collection<String> = store.loadManifest()?.keys.orEmpty()

    override fun findPsiFile(filePath: String): PsiFile? =
        runCatching { session.findKtFile(filePath) }.getOrNull()

    override fun <T> withReadAccess(action: () -> T): T {
        throwIfCancelled()
        return session.withReadAccess {
            throwIfCancelled()
            action()
        }
    }

    // Standalone Phase 2 must hold the session write lock so K2 FIR resolution does
    // not run concurrently with foreground read operations on the same session.
    override fun <T> withExclusiveAccess(action: () -> T): T {
        throwIfCancelled()
        return session.withExclusiveAccess {
            throwIfCancelled()
            action()
        }
    }

    override fun isCancelled(): Boolean = cancelled()

    private fun throwIfCancelled() {
        if (cancelled()) {
            throw CancellationException("Reference indexing cancelled")
        }
    }
}
