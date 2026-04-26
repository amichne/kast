package io.github.amichne.kast.shared.analysis

import com.intellij.psi.PsiFile

interface ReferenceIndexEnvironment {
    fun allFilePaths(): Collection<String>

    fun findPsiFile(filePath: String): PsiFile?

    fun <T> withReadAccess(action: () -> T): T

    fun isCancelled(): Boolean
}
