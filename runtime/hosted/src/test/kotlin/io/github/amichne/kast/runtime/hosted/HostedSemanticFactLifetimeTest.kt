package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.topology.build.SemanticCallbackLookup
import io.github.amichne.kast.topology.build.SemanticCallbackStoreFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedSemanticFactLifetimeTest : HostedSemanticFactFixture() {
    @Test
    fun `changed limits retire the old store and retained cache port before replacement`() {
        val service = HostedSemanticCallbackFacts()
        val snapshot = snapshot(owner.admit())
        val summary = summary(snapshot.authority)
        val prior = service.selectStore(ReadLimits.Default).value()
        val oldPort = HostedCallbackFactCache(snapshot, prior, counts)
        oldPort.retain(summary)
        assertSame(prior, service.selectStore(ReadLimits.Default).value())
        val limits =
            ReadLimits.resolve(properties = mapOf(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.propertyKey to "1"))
                .value()
        val replacement = service.selectStore(limits).value()
        assertNotSame(prior, replacement)
        assertEquals(
            CallbackSummaryCacheLookup.Miss,
            oldPort.find(summary.formal) { error("Retired facts cannot request compiler restoration") },
        )
        oldPort.retain(summary)
        assertEquals(
            SemanticCallbackLookup.Rejected(SemanticCallbackStoreFailure.Retired),
            prior.find(snapshot, summary.formal),
        )
        assertEquals(SemanticCallbackLookup.Missing, replacement.find(snapshot, summary.formal))
        val freshPort = HostedCallbackFactCache(snapshot, replacement, counts)
        freshPort.retain(summary)
        assertEquals(
            CallbackSummaryCacheLookup.Found(summary),
            freshPort.find(summary.formal) { error("Current fact needs no restoration") },
        )
    }

    @Test
    fun `service disposal is terminal for store selection and retained port use`() {
        val service = HostedSemanticCallbackFacts()
        val snapshot = snapshot(owner.admit())
        val summary = summary(snapshot.authority)
        val selected = service.selectStore(ReadLimits.Default).value()
        val port = HostedCallbackFactCache(snapshot, selected, counts)
        port.retain(summary)
        service.dispose()
        assertEquals(
            Refinement.Rejected(HostedSemanticCallbackFacts.StoreSelectionFailure.RETIRED),
            service.selectStore(ReadLimits.Default),
        )
        assertEquals(
            CallbackSummaryCacheLookup.Miss,
            port.find(summary.formal) { error("Disposed facts cannot request compiler restoration") },
        )
        port.retain(summary)
        assertEquals(
            SemanticCallbackLookup.Rejected(SemanticCallbackStoreFailure.Retired),
            selected.find(snapshot, summary.formal),
        )
    }
}
