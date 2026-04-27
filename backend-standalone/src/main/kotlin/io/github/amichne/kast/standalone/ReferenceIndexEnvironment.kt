package io.github.amichne.kast.standalone

import com.intellij.psi.PsiFile
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment

internal class ReferenceIndexEnvironment(
    private val session: AnalysisSession,
    private val store: SqliteSourceIndexStore,
    private val cancelled: () -> Boolean,
) : ReferenceIndexEnvironment {
    override fun allFilePaths(): Collection<String> = store.loadManifest()?.keys.orEmpty()

    override fun findPsiFile(filePath: String): PsiFile? =
        runCatching { session.findKtFile(filePath) }.getOrNull()

    override fun <T> withReadAccess(action: () -> T): T = session.withReadAccess(action)

    override fun isCancelled(): Boolean = cancelled()
}
