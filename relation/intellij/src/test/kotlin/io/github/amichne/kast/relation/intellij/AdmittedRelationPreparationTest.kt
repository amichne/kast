package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackSummaryCachePort
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationMeaning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Production effect order with detached admission observations, not a native compiler-success fixture. */
class AdmittedRelationPreparationTest {
    @Test
    fun `scope and subject rejection remove all optional preparation calls and preserve exact failure`() {
        for (reason in
            listOf(
                RelationCompilerRejection.SCOPE_REJECTED,
                RelationCompilerRejection.STALE_SELECTOR,
                RelationCompilerRejection.OUTSIDE_SCOPE,
                RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE,
            )) {
            var preparations = 0
            val actual =
                admitThenPrepareRelation<Unit>(
                    admit = { Refinement.Rejected(reason) },
                    prepare = {
                        preparations++
                        CallbackSummaryCachePort.Disabled
                    },
                    evaluate = { _, _ -> error("Rejected admission cannot evaluate a relation") },
                )
            assertEquals(RelationCompilation.Rejected(reason), actual)
            assertEquals(0, preparations, "Previous prepare-before-admission order invoked preparation once")
        }
    }

    @Test
    fun `admitted request prepares once before evaluation and preserves both admitted identity and result`() {
        val request = RelationReadTest().request(RelationMeaning.Callers)
        val expected = RelationCompilation.Rejected(RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE)
        val order = mutableListOf<String>()
        val actual =
            admitThenPrepareRelation(
                admit = {
                    order += "admit"
                    Refinement.Refined(request)
                },
                prepare = {
                    order += "prepare"
                    CallbackSummaryCachePort.Disabled
                },
                evaluate = { admitted, cache ->
                    order += "evaluate"
                    assertSame(request, admitted)
                    assertSame(CallbackSummaryCachePort.Disabled, cache)
                    expected
                },
            )
        assertSame(expected, actual)
        assertEquals(listOf("admit", "prepare", "evaluate"), order)
    }
}
