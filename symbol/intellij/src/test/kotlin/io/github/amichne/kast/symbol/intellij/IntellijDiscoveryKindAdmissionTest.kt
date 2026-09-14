package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijDiscoveryKindAdmissionTest {
    @Test
    fun `class discovery filters proven enum entries rather than qualifying unknown evidence`() {
        assertEquals(
            IntellijDiscoveryItemAdmission.FILTERED,
            admit(IntellijDiscoveryItemCompilerKindResult.EnumEntry),
        )
    }

    @Test
    fun `enum entries do not acquire an unrelated supported declaration kind`() {
        for (kind in CompilerSymbolKind.entries) {
            assertEquals(
                IntellijDiscoveryItemAdmission.FILTERED,
                admit(IntellijDiscoveryItemCompilerKindResult.EnumEntry, setOf(kind)),
            )
        }
    }

    @Test
    fun `supported declaration kinds retain admission when requested`() {
        for (kind in CompilerSymbolKind.entries) {
            assertEquals(
                IntellijDiscoveryItemAdmission.ADMITTED,
                admit(IntellijDiscoveryItemCompilerKindResult.Found(kind), setOf(kind)),
            )
        }
    }

    @Test
    fun `class discovery filters a proven function`() {
        assertEquals(
            IntellijDiscoveryItemAdmission.FILTERED,
            admit(IntellijDiscoveryItemCompilerKindResult.Found(CompilerSymbolKind.FUNCTION)),
        )
    }

    @Test
    fun `unknown PSI remains unsupported instead of becoming a silent exclusion`() {
        assertEquals(
            IntellijDiscoveryItemAdmission.UNSUPPORTED,
            admit(IntellijDiscoveryItemCompilerKindResult.Unsupported),
        )
    }

    @Test
    fun `an absent kind restriction does not introduce a new classification requirement`() {
        assertEquals(
            IntellijDiscoveryItemAdmission.ADMITTED,
            SymbolDiscoveryConstraints.None.admitKind(Candidate) {
                error("Unrestricted discovery must not request kind evidence here")
            },
        )
    }

    private fun admit(
        classification: IntellijDiscoveryItemCompilerKindResult,
        requestedKinds: Set<CompilerSymbolKind> = setOf(CompilerSymbolKind.CLASSLIKE),
    ): IntellijDiscoveryItemAdmission {
        val kinds =
            when (val refined = SymbolDiscoveryDeclarationKinds.from(requestedKinds)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> error("Test requires a non-empty kind restriction")
            }
        return SymbolDiscoveryConstraints.None.copy(declarationKinds = kinds).admitKind(Candidate) { classification }
    }

    private object Candidate : NavigationItem {
        override fun getName(): String = "ACTIVE"

        override fun getPresentation(): ItemPresentation? = null

        override fun navigate(requestFocus: Boolean) {
            error("Kind admission must not navigate")
        }

        override fun canNavigate(): Boolean = false

        override fun canNavigateToSource(): Boolean = false
    }
}
