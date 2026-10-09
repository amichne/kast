package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationMeaning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CallbackProviderSiteAdmissionTest {
    @Test
    fun `excluded callbacks check expiry without charging eligible work and stop before native site access`() {
        val request = RelationReadTest().request(RelationMeaning.Callers)
        var now = 0L
        val allowance = IntellijRelationAllowance { now }
        val collector = IntellijRelationCollector(request, allowance = allowance)
        var checks = 0
        repeat(20) {
            assertEquals(
                Refinement.Refined(RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED),
                admitCallbackProviderSite({ checks++ }, collector::callbackExpiry) {
                    RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED
                },
            )
        }
        assertEquals(CallbackWorkAdmission.READY, collector.admitCallbackWork())
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.TIME_LIMIT_REACHED),
            admitCallbackProviderSite({ checks++ }, collector::callbackExpiry) {
                error("Expired search must stop before inspecting even an excluded native site")
            },
        )
        assertEquals(21, checks)
        assertEquals(1L, allowance.examined)
    }

    @Test
    fun `platform cancellation propagates before expiry and native site access`() {
        val cancelled = ProcessCanceledException()
        val thrown =
            assertThrows(ProcessCanceledException::class.java) {
                admitCallbackProviderSite(
                    { throw cancelled },
                    { error("Native cancellation must precede expiry observation") },
                    { error("Native cancellation must precede site observation") },
                )
            }
        assertSame(cancelled, thrown)
    }

    @Test
    fun `current callback preserves every classification in cancellation expiry site order`() {
        for (site in RelationProviderScopeAdmission.entries) {
            val events = mutableListOf<Stage>()
            assertEquals(
                Refinement.Refined(site),
                admitCallbackProviderSite(
                    { events += Stage.CANCELLATION },
                    {
                        events += Stage.EXPIRY
                        CallbackExpiryAdmission.CURRENT
                    },
                    {
                        events += Stage.SITE
                        site
                    },
                ),
            )
            assertEquals(listOf(Stage.CANCELLATION, Stage.EXPIRY, Stage.SITE), events)
        }
    }

    private enum class Stage {
        CANCELLATION,
        EXPIRY,
        SITE,
    }
}
