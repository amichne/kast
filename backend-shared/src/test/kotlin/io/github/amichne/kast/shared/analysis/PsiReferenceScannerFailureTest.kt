package io.github.amichne.kast.shared.analysis

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PsiReferenceScannerFailureTest {
    @Test
    fun `one recursive platform reference does not abort indexing`() {
        assertNull(recoverRuntimePsiFailure<Nothing> { throw StackOverflowError("K2 FIR recursion") })
    }
}
