package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RelationProviderScopeObservationTest {
    @Test
    fun `finite provider decisions preserve identity and distinct bounded evidence`() {
        val events = mutableListOf<Count>()
        val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    events += Count(counter, contributor, amount)
                }

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
                    error("Scope classification alone must not manufacture termination")
                }
            }
        repeat(20) {
            assertSame(
                RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
                RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED.observed(observation),
            )
        }
        assertSame(
            RelationProviderScopeAdmission.ADMITTED,
            RelationProviderScopeAdmission.ADMITTED.observed(observation),
        )
        assertSame(
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED,
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED.observed(observation),
        )
        assertSame(
            RelationProviderScopeAdmission.UNAVAILABLE,
            RelationProviderScopeAdmission.UNAVAILABLE.observed(observation),
        )
        assertEquals(
            List(20) {
                Count(IntellijReadCounter.RELATION_PROVIDER_SCOPE_SOURCE_EXCLUDED, IntellijReadContributor.NONE, 1)
            } +
                listOf(
                    Count(IntellijReadCounter.RELATION_PROVIDER_SCOPE_ADMITTED, IntellijReadContributor.NONE, 1),
                    Count(
                        IntellijReadCounter.RELATION_PROVIDER_SCOPE_LIBRARY_EXCLUDED,
                        IntellijReadContributor.NONE,
                        1,
                    ),
                    Count(IntellijReadCounter.RELATION_PROVIDER_SCOPE_UNAVAILABLE, IntellijReadContributor.NONE, 1),
                ),
            events,
        )
    }

    private data class Count(
        val counter: IntellijReadCounter,
        val contributor: IntellijReadContributor,
        val amount: Int,
    )
}
