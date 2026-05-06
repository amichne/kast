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
    //
    // However, blocking indefinitely on write-lock acquisition turns Phase 2 into a
    // PENDING WRITE WAITER in Java's fair ReentrantReadWriteLock, which prevents any
    // new read from proceeding — starving foreground operations.
    //
    // Fix: use tryWrite with a short timeout so that, on contention, Phase 2 dequeues
    // itself from the write-waiter list, lets pending reads through, and retries.
    override fun <T> withExclusiveAccess(action: () -> T): T {
        throwIfCancelled()
        while (true) {
            throwIfCancelled()
            val result: T? = session.tryWrite(PHASE2_WRITE_TIMEOUT_MS) {
                throwIfCancelled()
                action()
            }
            if (result != null) return result
            Thread.sleep(PHASE2_WRITE_RETRY_BACKOFF_MS)
        }
    }

    private companion object {
        /** Maximum milliseconds Phase 2 waits for the write lock before yielding. */
        const val PHASE2_WRITE_TIMEOUT_MS = 300L

        /** Backoff between retry attempts when the write lock is not acquired. */
        const val PHASE2_WRITE_RETRY_BACKOFF_MS = 50L
    }

    override fun isCancelled(): Boolean = cancelled()

    private fun throwIfCancelled() {
        if (cancelled()) {
            throw CancellationException("Reference indexing cancelled")
        }
    }
}
