package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedNativePageDiagnosticsTest {
    @Test
    fun `native page counters retain explicit zeros and completed pages on failure`() {
        for (outcome in
            listOf(HostedDiagnosticOutcome.Completed, HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostic.count(IntellijReadCounter.NATIVE_DISCOVERY_PAGES)
            diagnostic.finish(outcome)
            diagnostic.count(IntellijReadCounter.NATIVE_RELATION_PAGES)
            val pages =
                receipts.single().counters.filter {
                    it.counter == IntellijReadCounter.NATIVE_DISCOVERY_PAGES ||
                        it.counter == IntellijReadCounter.NATIVE_RELATION_PAGES
                }
            assertEquals(
                listOf(
                    HostedNativeCount(IntellijReadCounter.NATIVE_DISCOVERY_PAGES, IntellijReadContributor.NONE, 1),
                    HostedNativeCount(IntellijReadCounter.NATIVE_RELATION_PAGES, IntellijReadContributor.NONE, 0),
                ),
                pages,
            )
            assertEquals(outcome, receipts.single().outcome)
        }
    }
}
