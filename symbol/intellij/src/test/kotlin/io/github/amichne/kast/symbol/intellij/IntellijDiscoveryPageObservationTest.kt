package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMeasurements
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.intellij.read.IntellijDiscoveryPageMeasurements
import io.github.amichne.kast.workspace.intellij.read.IntellijDiscoveryPageOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijDiscoveryPagePublication
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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

    @Test
    fun `produced nonzero page reaches default logger once with exact tuple`() = runTest {
        val lines = mutableListOf<String>()
        withLogger(lines::add).use {
            // Cross-module test access to the existing internal factory, with its real default logger binding.
            val observation =
                Class.forName("io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadDiagnosticEncodingKt")
                    .getMethod("hostedReadDiagnostics", ReadLimits::class.java)
                    .invoke(null, ReadLimits.Default) as IntellijReadObservation
            val identity =
                observation.javaClass.getMethod("getIdentity").invoke(observation)
                    as io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity
            val adapter = IntellijNativeDiscoveryAdapter(observation = observation)
            val produced = IntellijNativeDiscoveryResult.Discovered(page())
            assertSame(produced, adapter.observePage { produced })
            assertEquals(1, lines.size, "A produced page must reach the default logger exactly once")
            assertTrue(lines.single().startsWith("kast_discovery_page "))
            val record = Json.parseToJsonElement(lines.single().removePrefix("kast_discovery_page ")).jsonObject
            assertEquals(
                setOf(
                    "schemaVersion",
                    "readId",
                    "pageOrdinal",
                    "outcome",
                    "examined_work_units",
                    "inventory_files",
                    "reacquired_files",
                    "examined_leaves",
                    "inventory_nanoseconds",
                    "declaration_scan_nanoseconds",
                    "projection_nanoseconds",
                ),
                record.keys,
            )
            assertEquals(16, record.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals(identity.value.toString(), record.getValue("readId").jsonPrimitive.content)
            assertEquals(1, record.getValue("pageOrdinal").jsonPrimitive.int)
            assertEquals("COMPLETE", record.getValue("outcome").jsonPrimitive.content)
            for ((field, expected) in
                listOf(
                    "examined_work_units" to 11L,
                    "inventory_files" to 12L,
                    "reacquired_files" to 13L,
                    "examined_leaves" to 14L,
                    "inventory_nanoseconds" to 15L,
                    "declaration_scan_nanoseconds" to 16L,
                    "projection_nanoseconds" to 17L,
                )) {
                assertEquals(expected, record.getValue(field).jsonPrimitive.long, field)
            }
        }
    }

    @Test
    fun `read restarts publish only the returned page and rejection publishes none`() = runTest {
        val records = mutableListOf<IntellijDiscoveryPageMeasurements>()
        val observation =
            object : IntellijReadObservation {
                override fun discoveryPage(
                    measurements: IntellijDiscoveryPageMeasurements
                ): IntellijDiscoveryPagePublication {
                    records += measurements
                    return IntellijDiscoveryPagePublication.PUBLISHED
                }

                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) =
                    Unit

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
                    fail<Unit>("Unexpected termination")
            }
        val adapter = IntellijNativeDiscoveryAdapter(observation = observation)
        val produced = IntellijNativeDiscoveryResult.Discovered(qualifiedPage())
        var tries = 0
        assertSame(
            produced,
            adapter.observePage {
                // The effect represents two restartable read calculations and one returned page.
                repeat(2) {
                    tries++
                    assertTrue(records.isEmpty())
                }
                produced
            },
        )
        assertEquals(2, tries)
        assertEquals(1, records.size)
        assertEquals(IntellijDiscoveryPageOutcome.QUALIFIED, records.single().outcome)
        val rejected = IntellijNativeDiscoveryResult.Rejected(IntellijNativeDiscoveryRejection.DUMB_MODE)
        assertSame(rejected, adapter.observePage { rejected })
        val scopeRejected = IntellijNativeDiscoveryResult.ScopeRejected(emptySet())
        assertSame(scopeRejected, adapter.observePage { scopeRejected })
        assertEquals(1, records.size)
        val cancelled = com.intellij.openapi.progress.ProcessCanceledException()
        try {
            adapter.observePage { throw cancelled }
            fail<Unit>("Cancellation must propagate")
        } catch (actual: com.intellij.openapi.progress.ProcessCanceledException) {
            assertSame(cancelled, actual)
        }
        assertEquals(1, records.size)
    }

    @Test
    fun `failed diagnostic publication preserves the identical semantic page`() = runTest {
        var offers = 0
        val observation =
            object : IntellijReadObservation {
                override fun discoveryPage(
                    measurements: IntellijDiscoveryPageMeasurements
                ): IntellijDiscoveryPagePublication {
                    offers++
                    return IntellijDiscoveryPagePublication.FAILED
                }

                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) =
                    Unit

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
                    fail<Unit>("Unexpected termination")
            }
        val adapter = IntellijNativeDiscoveryAdapter(observation = observation)
        val produced = IntellijNativeDiscoveryResult.Discovered(page())
        assertSame(produced, adapter.observePage { produced })
        assertEquals(1, offers)
    }

    private fun withLogger(info: (String) -> Unit): AutoCloseable {
        val previous = Logger.getFactory()
        Logger.setFactory(
            Logger.Factory { category ->
                assertEquals("#io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadDiagnostics", category)
                object : Logger() {
                    override fun isDebugEnabled() = false

                    override fun debug(message: String?, throwable: Throwable?) = fail<Unit>("Unexpected debug")

                    override fun info(message: String?, throwable: Throwable?) {
                        assertNull(throwable)
                        info(requireNotNull(message))
                    }

                    override fun warn(message: String?, throwable: Throwable?) = fail<Unit>("Unexpected warning")

                    override fun error(message: String?, throwable: Throwable?, vararg details: String?) =
                        fail<Unit>("Unexpected error")
                }
            }
        )
        return AutoCloseable { Logger.setFactory(previous) }
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
