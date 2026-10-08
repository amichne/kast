package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilation
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationKind
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Grant sequencing over independently admitted input facts; this does not establish compiler or physical-file binding.
 */
class ScopedExactReacquisitionBudgetTest {
    @Test
    fun `one remaining work unit rejects local reacquisition before content or compiler effects`() {
        val original = localSignature()
        var retained = original
        var lookupWork = 0
        val result =
            confirmScopedExactReacquisition(
                budget =
                    ResourceBudget(
                        ResultLimit.parse(1).refined(),
                        WorkUnitLimit.parse(1).refined(),
                        ElapsedTimeLimitMillis.parse(100).refined(),
                    ),
                clock = IntellijReadNanoClock { 0L },
                captureWork = { 0L },
                checkContent = { error("Unexpected owning-document capture under an exhausted grant") },
                confirmCompiler = {
                    retained = localSignature()
                    error("Unexpected compiler confirmation under an exhausted grant")
                },
                chargeLookup = { lookupWork++ },
            )
        assertEquals(ExactRevalidationCompilation.Rejected(ExactRevalidationRejection.WORK_LIMIT_REACHED), result)
        assertEquals(0, lookupWork)
        assertSame(original, retained)
        assertEquals(original.address, retained.address)
        assertEquals(
            CompilerSymbolIdentity.fromCanonicalSignature(original),
            CompilerSymbolIdentity.fromCanonicalSignature(retained),
        )
    }

    @Test
    fun `expired clock grant rejects before content capture`() {
        val case = BudgetCase(listOf(0L, 1_000_000L))
        assertEquals(ExactRevalidationCompilation.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED), case.run())
        case.assertEffects(emptyList(), 0L, 0)
    }

    @Test
    fun `elapsed content capture prevents compiler lookup`() {
        val case = BudgetCase(listOf(0L, 0L, 1_000_000L))
        assertEquals(ExactRevalidationCompilation.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED), case.run())
        case.assertEffects(listOf(Effect.CONTENT_CAPTURE), 2L, 0)
    }

    @Test
    fun `compiler confirmation beyond clock grant cannot publish success`() {
        val case = BudgetCase(listOf(0L, 0L, 0L, 1_000_000L))
        assertEquals(ExactRevalidationCompilation.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED), case.run())
        case.assertEffects(listOf(Effect.CONTENT_CAPTURE, Effect.LOOKUP_CHARGE, Effect.COMPILER_CONFIRMATION), 2L, 1)
    }

    @Test
    fun `admitted grant preserves local compiler facts and charges capture plus lookup`() {
        val case = BudgetCase(listOf(0L, 0L, 0L, 0L))
        val result = case.run() as ExactRevalidationCompilation.Confirmed
        assertSame(case.input, result.evidence)
        assertEquals(
            LocalDeclarationKind.PROPERTY,
            (result.evidence.signature as CanonicalCompilerSignature.LocalProperty).address.kind,
        )
        assertEquals(ExactDeclarationTextRange.parse(10, 20).refined(), result.evidence.range)
        assertEquals(case.input.compilerIdentity, result.evidence.compilerIdentity)
        case.assertEffects(listOf(Effect.CONTENT_CAPTURE, Effect.LOOKUP_CHARGE, Effect.COMPILER_CONFIRMATION), 2L, 1)
    }

    private enum class Effect {
        CONTENT_CAPTURE,
        LOOKUP_CHARGE,
        COMPILER_CONFIRMATION,
    }

    private inner class BudgetCase(observations: List<Long>) {
        private val clocks = ArrayDeque(observations)
        private val effects = mutableListOf<Effect>()
        private var captureWork = 0L
        private var lookupWork = 0
        val input =
            localSignature().let { signature ->
                CompilerGroundedSymbolEvidence.fromBoundary(
                        signature.address.file,
                        10,
                        20,
                        "value",
                        null,
                        CompilerSymbolKind.PROPERTY,
                        signature,
                    )
                    .refined()
            }

        fun run(): ExactRevalidationCompilation =
            confirmScopedExactReacquisition(
                budget =
                    ResourceBudget(
                        ResultLimit.parse(1).refined(),
                        WorkUnitLimit.parse(3).refined(),
                        ElapsedTimeLimitMillis.parse(1).refined(),
                    ),
                clock =
                    IntellijReadNanoClock {
                        assertTrue(clocks.isNotEmpty(), "Unexpected clock observation")
                        clocks.removeFirst()
                    },
                captureWork = { captureWork },
                checkContent = { maximum ->
                    assertEquals(WorkUnitLimit.parse(2).refined(), maximum)
                    effects += Effect.CONTENT_CAPTURE
                    captureWork += 2L
                    Refinement.Refined(Unit)
                },
                confirmCompiler = {
                    effects += Effect.COMPILER_CONFIRMATION
                    ExactRevalidationCompilation.Confirmed(input)
                },
                chargeLookup = {
                    effects += Effect.LOOKUP_CHARGE
                    lookupWork++
                },
            )

        fun assertEffects(expected: List<Effect>, expectedCapture: Long, expectedLookup: Int) {
            assertEquals(expected, effects)
            assertEquals(expectedCapture, captureWork)
            assertEquals(expectedLookup, lookupWork)
            assertTrue(clocks.isEmpty(), "Unconsumed clock observations")
        }
    }

    private fun localSignature(): CanonicalCompilerSignature.LocalProperty {
        val owner = CanonicalCompilerSignature.function("sample.owner", null, emptyList(), emptyList(), 0).refined()
        val address =
            LocalDeclarationAddress.create(
                    LocalDeclarationAddress.restoreFile("workspace", "/workspace/Main.kt").refined(),
                    LocalDeclarationKind.PROPERTY,
                    ExactDeclarationTextRange.parse(10, 20).refined(),
                    CompilerSymbolIdentity.fromCanonicalSignature(owner),
                    ExactDeclarationTextRange.parse(0, 100).refined(),
                    emptyList(),
                )
                .refined()
        return CanonicalCompilerSignature.localProperty(address, "kotlin.String", LocalPropertyMutability.VAL).refined()
            as CanonicalCompilerSignature.LocalProperty
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid independently specified fixture: $failure")
        }
}
