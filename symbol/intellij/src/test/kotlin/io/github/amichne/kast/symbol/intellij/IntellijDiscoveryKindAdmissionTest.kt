package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijDiscoveryKindAdmissionTest {
    @Test
    fun `a name selection over the classlike family cannot select a matched enum entry`() {
        for (match in SymbolDiscoveryMatch.entries) {
            assertEquals(
                IntellijDiscoveryConstraintAdmission.UNSELECTABLE_ENUM_ENTRY,
                admit(IntellijDiscoveryItemCompilerKindResult.EnumEntry, PUBLIC_DEFAULT_KINDS, name(match)),
            )
            assertEquals(
                IntellijDiscoveryConstraintAdmission.UNSELECTABLE_ENUM_ENTRY,
                admit(
                    IntellijDiscoveryItemCompilerKindResult.EnumEntry,
                    setOf(CompilerSymbolKind.CLASSLIKE),
                    name(match),
                ),
            )
        }
    }

    @Test
    fun `a name selection without the classlike family proves the entry outside its kinds`() {
        for (kind in CompilerSymbolKind.entries - CompilerSymbolKind.CLASSLIKE) {
            assertEquals(
                IntellijDiscoveryConstraintAdmission.FILTERED,
                admit(
                    IntellijDiscoveryItemCompilerKindResult.EnumEntry,
                    setOf(kind),
                    name(SymbolDiscoveryMatch.EXACT_NAME),
                ),
            )
        }
    }

    @Test
    fun `an all selection filters proven enum entries rather than qualifying unknown evidence`() {
        for (kind in CompilerSymbolKind.entries) {
            assertEquals(
                IntellijDiscoveryConstraintAdmission.FILTERED,
                admit(IntellijDiscoveryItemCompilerKindResult.EnumEntry, setOf(kind), ALL),
            )
        }
    }

    @Test
    fun `supported declaration kinds retain admission when requested`() {
        for (target in listOf(ALL, name(SymbolDiscoveryMatch.EXACT_NAME))) {
            for (kind in CompilerSymbolKind.entries) {
                assertEquals(
                    IntellijDiscoveryConstraintAdmission.ADMITTED,
                    admit(IntellijDiscoveryItemCompilerKindResult.Found(kind), setOf(kind), target),
                )
            }
        }
    }

    @Test
    fun `a same-named proven function stays filtered from a classlike name selection`() {
        assertEquals(
            IntellijDiscoveryConstraintAdmission.FILTERED,
            admit(
                IntellijDiscoveryItemCompilerKindResult.Found(CompilerSymbolKind.FUNCTION),
                setOf(CompilerSymbolKind.CLASSLIKE),
                name(SymbolDiscoveryMatch.EXACT_NAME),
            ),
        )
    }

    @Test
    fun `unknown PSI remains unsupported instead of becoming a silent exclusion`() {
        for (target in listOf(ALL, name(SymbolDiscoveryMatch.EXACT_NAME))) {
            assertEquals(
                IntellijDiscoveryConstraintAdmission.UNSUPPORTED,
                admit(IntellijDiscoveryItemCompilerKindResult.Unsupported, setOf(CompilerSymbolKind.CLASSLIKE), target),
            )
        }
    }

    @Test
    fun `an absent kind restriction does not introduce a new classification requirement`() {
        assertEquals(
            IntellijDiscoveryConstraintAdmission.ADMITTED,
            SymbolDiscoveryConstraints.None.admitKind(
                Candidate,
                { error("Unrestricted discovery must not request kind evidence here") },
                name(SymbolDiscoveryMatch.EXACT_NAME),
            ),
        )
    }

    private fun admit(
        classification: IntellijDiscoveryItemCompilerKindResult,
        requestedKinds: Set<CompilerSymbolKind>,
        target: SymbolDiscoveryTarget,
    ): IntellijDiscoveryConstraintAdmission {
        val kinds =
            when (val refined = SymbolDiscoveryDeclarationKinds.from(requestedKinds)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> error("Test requires a non-empty kind restriction")
            }
        return SymbolDiscoveryConstraints.None.copy(declarationKinds = kinds)
            .admitKind(
                Candidate,
                { classification },
                target,
            )
    }

    private fun name(match: SymbolDiscoveryMatch): SymbolDiscoveryTarget =
        SymbolDiscoveryTarget.Name(
            SymbolNameDiscoveryKind.SYMBOL,
            when (val pattern = SymbolDiscoveryPattern.parse("ACTIVE")) {
                is Refinement.Refined -> pattern.value
                is Refinement.Rejected -> error("Test requires a valid pattern")
            },
            match,
        )

    private object Candidate : NavigationItem {
        override fun getName(): String = "ACTIVE"

        override fun getPresentation(): ItemPresentation? = null

        override fun navigate(requestFocus: Boolean) {
            error("Kind admission must not navigate")
        }

        override fun canNavigate(): Boolean = false

        override fun canNavigateToSource(): Boolean = false
    }

    private companion object {
        val ALL: SymbolDiscoveryTarget = SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL)
        val PUBLIC_DEFAULT_KINDS =
            setOf(
                CompilerSymbolKind.CLASSLIKE,
                CompilerSymbolKind.FUNCTION,
                CompilerSymbolKind.PROPERTY,
                CompilerSymbolKind.TYPE_ALIAS,
            )
    }
}
