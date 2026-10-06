package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Resolution outcome instrumentation only; fixtures do not replace or claim live K2 resolution. */
class ResolvedCallableObservationTest {
    @Test
    fun `each expected resolution rejection preserves its finite diagnostic cause`() {
        val expected =
            listOf(
                IntellijResolvedCallableFailure.COMPILER_IDENTITY_UNAVAILABLE to
                    IntellijReadTermination.K2_CALLABLE_IDENTITY_UNAVAILABLE,
                IntellijResolvedCallableFailure.UNSUPPORTED_MODULE to
                    IntellijReadTermination.K2_CALLABLE_MODULE_UNSUPPORTED,
                IntellijResolvedCallableFailure.UNSUPPORTED_ORIGIN to
                    IntellijReadTermination.K2_CALLABLE_ORIGIN_UNSUPPORTED,
                IntellijResolvedCallableFailure.MODULE_IDENTITY_UNAVAILABLE to
                    IntellijReadTermination.K2_CALLABLE_MODULE_IDENTITY_UNAVAILABLE,
                IntellijResolvedCallableFailure.PARAMETER_OWNER_UNAVAILABLE to
                    IntellijReadTermination.K2_PARAMETER_OWNER_UNAVAILABLE,
                IntellijResolvedCallableFailure.PARAMETER_POSITION_UNAVAILABLE to
                    IntellijReadTermination.K2_PARAMETER_POSITION_UNAVAILABLE,
            )
        assertEquals(IntellijResolvedCallableFailure.entries.toSet(), expected.map { it.first }.toSet())
        for ((cause, signal) in expected) {
            val recording = Recording()
            val value = IntellijK2ResolvedDeclaration.Unsupported(cause)
            assertSame(value, value.observedResolutionBy(recording))
            assertEquals(listOf(signal), recording.values)
            assertSame(value, value.observedResolutionBy(IntellijReadObservation.None))
        }
    }

    @Test
    fun `resolved source-less success remains distinct from an unresolved symbol and preserves evidence`() {
        val signature =
            CanonicalCompilerSignature.function("kotlin.Function0.invoke", null, emptyList(), emptyList(), 0).refined()
        val callable =
            SourceLessCallable.fromCompiler(
                    signature,
                    CompilerSymbolKind.FUNCTION,
                    SourceLessCallableOrigin.LIBRARY,
                    SourceLessCallableModuleKind.BUILTINS,
                    SourceLessCallableModuleName.parse("Built-ins").refined(),
                )
                .refined()
        val value = IntellijK2ResolvedDeclaration.SourceLess(callable)
        val recording = Recording()
        assertSame(value, value.observedResolutionBy(recording))
        assertSame(value, value.observedResolutionBy(IntellijReadObservation.None))
        assertEquals(listOf(IntellijReadTermination.K2_SOURCELESS_CALLABLE_CONFIRMED), recording.values)
        IntellijK2ResolvedDeclaration.Unresolved.observedResolutionBy(recording)
        assertEquals(
            listOf(
                IntellijReadTermination.K2_SOURCELESS_CALLABLE_CONFIRMED,
                IntellijReadTermination.K2_UNRESOLVED_SYMBOL,
            ),
            recording.values,
        )
    }

    private class Recording : IntellijReadObservation by IntellijReadObservation.None {
        val values = mutableListOf<IntellijReadTermination>()

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            values += reason
        }
    }

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value
}
