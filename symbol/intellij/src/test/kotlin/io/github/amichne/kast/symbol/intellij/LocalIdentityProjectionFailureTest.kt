package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalIdentityProjectionFailureTest {
    @Test
    fun `local compiler work and owner depth limits remain explicit through exact revalidation`() {
        for (failure in
            listOf(
                LocalDeclarationProjectionFailure.WorkLimitReached,
                LocalDeclarationProjectionFailure.OwnerDepthExceeded,
            )) {
            val result = localProjectionRejected(failure, IntellijReadObservation.None)
            assertEquals(IntellijSymbolSelectorRejection.WORK_LIMIT_REACHED, result.reason)
            assertEquals(ExactRevalidationRejection.WORK_LIMIT_REACHED, result.reason.revalidationFailure())
            assertEquals(
                SymbolExactCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE,
                result.reason.toCompilerRejection(),
            )
        }
    }

    @Test
    fun `unproven local type remains compiler rejection without manufacturing work exhaustion`() {
        val result =
            localProjectionRejected(LocalDeclarationProjectionFailure.CompilerTypeError, IntellijReadObservation.None)
        assertEquals(IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE, result.reason)
        assertEquals(ExactRevalidationRejection.COMPILER_UNAVAILABLE, result.reason.revalidationFailure())
    }
}
