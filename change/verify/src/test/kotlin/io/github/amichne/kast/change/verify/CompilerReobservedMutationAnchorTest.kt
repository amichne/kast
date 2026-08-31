package io.github.amichne.kast.change.verify

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CompilerReobservedMutationAnchorTest {
    @Test
    fun `reobserved anchor accepts range growth but rejects borrowed compiler signature`() {
        val prior = VerifiedMutationFixture().plan.target.selector
        val qualified = (prior.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value
        val current = CompilerGroundedSymbolEvidence.fromBoundary(
            file = prior.file,
            rawStartInclusive = prior.range.startInclusive,
            rawEndExclusive = prior.range.endExclusive + 20,
            rawName = prior.name.value,
            rawQualifiedIdentity = qualified,
            kind = prior.kind,
            signature = prior.signature,
        ).refined()
        val borrowed = CompilerGroundedSymbolEvidence.fromBoundary(
            file = prior.file,
            rawStartInclusive = prior.range.startInclusive,
            rawEndExclusive = prior.range.endExclusive + 20,
            rawName = prior.name.value,
            rawQualifiedIdentity = qualified,
            kind = prior.kind,
            signature = CanonicalCompilerSignature.function(
                rawQualifiedIdentity = qualified,
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("kotlin.String"),
                rawTypeParameterCount = 0,
            ).refined(),
        ).refined()

        assertSame(current, CompilerReobservedMutationAnchor.admit(prior, current).refined().evidence)
        assertEquals(
            CompilerReobservedMutationAnchorFailure.COMPILER_EVIDENCE_MISMATCH,
            CompilerReobservedMutationAnchor.admit(prior, borrowed).rejected(),
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }
}
