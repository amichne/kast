package io.github.amichne.kast.shared.analysis

import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PsiReferenceScannerFailureTest {
    @Test
    fun `missing PSI is a closed file-local scan outcome`() {
        val environment = object : ReferenceIndexEnvironment {
            override fun findPsiFile(filePath: String): PsiFile? = null
            override fun <T> withReadAccess(action: () -> T): T = action()
            override fun <T> withExclusiveAccess(action: () -> T): T = action()
            override fun isCancelled(): Boolean = false
        }

        assertEquals(
            PsiRelationshipScanResult.PsiUnavailable,
            PsiReferenceScanner(environment).scanFileRelationships("/workspace/Missing.kt"),
        )
    }

    @Test
    fun `one recursive platform reference does not abort indexing`() {
        var resolutionLimited = false

        assertNull(
            recoverRuntimePsiFailure<Nothing>(
                onFailure = { resolutionLimited = true },
            ) {
                throw StackOverflowError("K2 FIR recursion")
            },
        )
        assertTrue(resolutionLimited)
    }
}
