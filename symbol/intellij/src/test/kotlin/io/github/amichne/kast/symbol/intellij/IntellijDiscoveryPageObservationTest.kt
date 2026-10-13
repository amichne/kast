package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.workspace.intellij.read.*
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntellijDiscoveryPageObservationTest {
    @Test
    fun `post-read observation preserves complete and qualified page identity once`() = runTest {
        for (page in listOf(page(), qualifiedPage())) {
            val counts = mutableListOf<IntellijReadCounter>()
            val observation =
                object : IntellijReadObservation {
                    override fun count(
                        counter: IntellijReadCounter,
                        contributor: IntellijReadContributor,
                        amount: Int,
                    ) {
                        assertEquals(1, amount)
                        counts += counter
                    }

                    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
                        fail<Unit>("No termination belongs to page publication")
                }
            val adapter = IntellijNativeDiscoveryAdapter(observation = observation)
            var reads = 0
            val result = IntellijNativeDiscoveryResult.Discovered(page)
            assertSame(
                result,
                adapter.observePage {
                    reads++
                    assertTrue(counts.isEmpty(), "No observation before the read returns")
                    result
                },
            )
            assertEquals(1, reads)
            assertEquals(listOf(IntellijReadCounter.NATIVE_DISCOVERY_PAGES), counts)
        }
    }

    @Test
    fun `pre-batch rejection preserves identity without a page observation`() = runTest {
        val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) =
                    fail<Unit>("A rejection has no produced page")

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
                    fail<Unit>("No termination belongs to page publication")
            }
        val adapter = IntellijNativeDiscoveryAdapter(observation = observation)
        val rejected = IntellijNativeDiscoveryResult.Rejected(IntellijNativeDiscoveryRejection.DUMB_MODE)
        assertSame(rejected, adapter.observePage { rejected })
    }

    private fun qualifiedPage() =
        SymbolDiscoveryOutcome.Qualified(
            page().batch,
            SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)).refined(),
        )

    private fun page(): SymbolDiscoveryOutcome.Complete {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val request =
            SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(lease, scope),
                SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL),
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(2).refined(),
                        WorkUnitLimit.parse(100).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(1000).refined(),
                ),
            )
        return SymbolDiscoveryOutcome.Complete(
            SymbolDiscoveryBatch.create(
                    request,
                    emptyList(),
                    SymbolDiscoveryByteCount.parse(0).refined(),
                    SymbolDiscoveryWorkCount.parse(11).refined(),
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.parse(99).refined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(17).refined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(15).refined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(16).refined(),
                    ),
                    SymbolDiscoveryMeasurements(
                        SymbolDiscoveryWorkCount.parse(12).refined(),
                        SymbolDiscoveryWorkCount.parse(13).refined(),
                        SymbolDiscoveryWorkCount.parse(14).refined(),
                    ),
                )
                .refined()
        )
    }

    private fun <V, E> Refinement<V, E>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
