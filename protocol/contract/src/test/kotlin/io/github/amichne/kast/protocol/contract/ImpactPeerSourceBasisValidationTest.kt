package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPeerSourceBasisValidationTest {
    private val fixture = ImpactPeerFixture()
    private val values = fixture.values

    @Test
    fun `foreign second seed cannot borrow the first source basis`() {
        val accounting = fixture.accounting().copy(seeds = values.bounded(listOf(fixture.source, fixture.target)))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            fixture.result().copy(impactAccounting = accounting).validateImpactAccounting(),
        )
        assertEquals(Refinement.Refined(Unit), fixture.result().validateImpactAccounting())
    }

    @Test
    fun `foreign requested site cannot become an ordinary source claim through matching question`() {
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            resultWithRequestedSite(fixture.target).validateImpactAccounting(),
        )
        assertEquals(Refinement.Refined(Unit), resultWithRequestedSite(fixture.source).validateImpactAccounting())
    }

    private fun resultWithRequestedSite(site: ImpactValueSiteReferenceDocument): QueryRunResult {
        val requested = values.bounded(listOf(site))
        val result = fixture.result()
        val source = result.question.from as QueryFromDocument.Impact
        return result.copy(
            question =
                result.question.copy(
                    from = source.copy(investigation = source.investigation.copy(requestedSites = requested))
                ),
            impactAccounting = fixture.accounting().copy(requestedSites = requested),
        )
    }
}
