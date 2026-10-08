package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.assertInstanceOf

/** Domain plan fixture; does not manufacture native authority. */
internal class HostedApprovalFixture {
    val plan =
        LiveAddDeclarationPlanCodec.decode(
                checkNotNull(javaClass.getResource("/live-add-declaration-plan-v2.json")).readText()
            )
            .approvalRefined()
    val owner = plan.basis.observation.reference.host
}

internal fun <T, F> Refinement<T, F>.approvalRefined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
