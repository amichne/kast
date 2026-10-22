package io.github.amichne.kast.workspace.intellij.read.hosted

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
import io.github.amichne.kast.workspace.intellij.read.IntellijDiscoveryPagePublication
import java.nio.file.Path
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

class HostedDiscoveryMeasurementsTest {
    @Test
    fun `complete and qualified pages preserve each exact measurement and read correlation`() {
        for (value in listOf(0L, 100L, 100000L)) {
            for (qualified in listOf(false, true)) {
                val records = mutableListOf<HostedDiscoveryPageRecord>()
                val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
                val diagnostic =
                    HostedReadDiagnostics(
                        { 0L },
                        publishDiscovery =
                            HostedDiscoveryPagePublisher {
                                records += it
                                HostedDiscoveryPagePublishOutcome.PUBLISHED
                            },
                        publish = receipts::add,
                    )
                val measurements = measurements(value, qualified)
                assertEquals(IntellijDiscoveryPagePublication.PUBLISHED, diagnostic.discoveryPage(measurements))
                diagnostic.finish(HostedDiagnosticOutcome.Completed)
                val record = records.single()
                assertSame(measurements, record.measurements)
                assertSame(diagnostic.identity, record.read)
                assertEquals(receipts.single().readId, record.read.value)
                assertEquals(HostedDiscoveryPageCounts(offered = 1, published = 1), receipts.single().discoveryPages)
                println("MVP_LOG_RECORD=" + record.encode())
                val encoded = Json.parseToJsonElement(record.encode()).jsonObject
                assertEquals(setOf("schemaVersion", "readId", "pageOrdinal", "outcome") + fields, encoded.keys)
                assertEquals(16, encoded.getValue("schemaVersion").jsonPrimitive.int)
                assertEquals(1, encoded.getValue("pageOrdinal").jsonPrimitive.int)
                assertEquals(
                    if (qualified) "QUALIFIED" else "COMPLETE",
                    encoded.getValue("outcome").jsonPrimitive.content,
                )
                fields.forEach { assertEquals(value, encoded.getValue(it).jsonPrimitive.long, it) }
                assertTrue(record.encode().toByteArray(Charsets.UTF_8).size <= 366)
            }
        }
    }

    @Test
    fun `fixed page capacity suppresses further publication and keeps exact offered count`() {
        val records = mutableListOf<HostedDiscoveryPageRecord>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic =
            HostedReadDiagnostics(
                { 0L },
                publishDiscovery =
                    HostedDiscoveryPagePublisher {
                        records += it
                        HostedDiscoveryPagePublishOutcome.PUBLISHED
                    },
                publish = receipts::add,
            )
        val measurement = measurements(100000)
        repeat(16) { assertEquals(IntellijDiscoveryPagePublication.PUBLISHED, diagnostic.discoveryPage(measurement)) }
        repeat(3) { assertEquals(IntellijDiscoveryPagePublication.SUPPRESSED, diagnostic.discoveryPage(measurement)) }
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        assertEquals(IntellijDiscoveryPagePublication.INACTIVE, diagnostic.discoveryPage(measurement))
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        assertEquals((1..16).toList(), records.map { it.ordinal.value })
        assertEquals(
            HostedDiscoveryPageCounts(offered = 19, published = 16, suppressed = 3),
            receipts.single().discoveryPages,
        )
        assertTrue(records.all { it.measurements.examinedWorkUnits.value == 100000L })
    }

    @Test
    fun `configured diagnostic count caps publication but never saturates measurement values`() {
        val limits = ReadLimits.resolve(mapOf("KAST_READ_DIAGNOSTIC_COUNT" to "2")).refined()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val records = mutableListOf<HostedDiscoveryPageRecord>()
        val diagnostic =
            HostedReadDiagnostics(
                { 0L },
                limits,
                publishDiscovery =
                    HostedDiscoveryPagePublisher {
                        records += it
                        HostedDiscoveryPagePublishOutcome.PUBLISHED
                    },
                publish = receipts::add,
            )
        repeat(2) {
            assertEquals(IntellijDiscoveryPagePublication.PUBLISHED, diagnostic.discoveryPage(measurements(100000)))
        }
        repeat(4) {
            assertEquals(IntellijDiscoveryPagePublication.SUPPRESSED, diagnostic.discoveryPage(measurements(100000)))
        }
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        assertEquals(listOf(1, 2), records.map { it.ordinal.value })
        assertEquals(
            HostedDiscoveryPageCounts(
                offered = 2,
                published = 2,
                suppressed = 2,
                qualification = HostedReadCallCountQualification.SATURATED,
            ),
            receipts.single().discoveryPages,
        )
        records.forEach { assertEquals(100000L, it.measurements.examinedLeaves.value) }
    }

    @Test
    fun `unavailable publisher remains distinct from successful publication and suppression`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        assertEquals(IntellijDiscoveryPagePublication.UNOBSERVED, diagnostic.discoveryPage(measurements(100)))
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        assertEquals(HostedDiscoveryPageCounts(offered = 1, unavailable = 1), receipts.single().discoveryPages)
    }

    @Test
    fun `default logger failure has finite outcome no retry and unchanged semantic completion`() {
        var pageCalls = 0
        val lines = mutableListOf<String>()
        withLogger { message ->
            if (message.startsWith("kast_discovery_page ")) {
                pageCalls++
                throw IllegalStateException("fixture publisher unavailable")
            }
            lines += message
        }
            .use {
                val diagnostic = hostedReadDiagnostics()
                val semanticOutcome = HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.QUALIFIED)
                assertEquals(IntellijDiscoveryPagePublication.FAILED, diagnostic.discoveryPage(measurements(100)))
                diagnostic.finish(semanticOutcome)
                assertEquals(1, pageCalls)
                val receipt = Json.parseToJsonElement(lines.single().removePrefix("kast_semantic_read ")).jsonObject
                val counts = receipt.getValue("discoveryPages").jsonObject
                assertEquals(1, counts.getValue("offered").jsonPrimitive.int)
                assertEquals(1, counts.getValue("failed").jsonPrimitive.int)
                assertEquals(0, counts.getValue("published").jsonPrimitive.int)
                assertEquals(0, counts.getValue("suppressed").jsonPrimitive.int)
                assertEquals(0, counts.getValue("unavailable").jsonPrimitive.int)
                assertEquals(
                    "QUALIFIED",
                    receipt.getValue("outcome").jsonObject.getValue("outcome").jsonPrimitive.content,
                )
            }
    }

    @Test
    fun `default publisher encodes successful measurements and keeps exact receipt correlation`() {
        val lines = mutableListOf<String>()
        withLogger(lines::add).use {
            val diagnostic = hostedReadDiagnostics()
            assertEquals(IntellijDiscoveryPagePublication.PUBLISHED, diagnostic.discoveryPage(measurements(100)))
            diagnostic.finish(HostedDiagnosticOutcome.Completed)
            assertEquals(2, lines.size)
            val page = Json.parseToJsonElement(lines[0].removePrefix("kast_discovery_page ")).jsonObject
            val receipt = Json.parseToJsonElement(lines[1].removePrefix("kast_semantic_read ")).jsonObject
            assertEquals(receipt.getValue("readId"), page.getValue("readId"))
            assertEquals(diagnostic.identity.value.toString(), page.getValue("readId").jsonPrimitive.content)
            fields.forEach { field -> assertEquals(100L, page.getValue(field).jsonPrimitive.long, field) }
            assertEquals(1, receipt.getValue("discoveryPages").jsonObject.getValue("published").jsonPrimitive.int)
        }
    }

    private fun measurements(value: Long, qualified: Boolean = false): IntellijDiscoveryPageMeasurements {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val request =
            SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(
                    lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                ),
                SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL),
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(2).refined(),
                        WorkUnitLimit.parse(100000).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(1000).refined(),
                ),
            )
        val work = SymbolDiscoveryWorkCount.parse(value).refined()
        val time = SymbolDiscoveryElapsedNanoseconds.parse(value).refined()
        val batch =
            SymbolDiscoveryBatch.create(
                    request,
                    emptyList(),
                    SymbolDiscoveryByteCount.parse(0).refined(),
                    work,
                    SymbolDiscoveryTimings(SymbolDiscoveryElapsedNanoseconds.parse(99).refined(), time, time, time),
                    SymbolDiscoveryMeasurements(work, work, work),
                )
                .refined()
        val outcome =
            if (qualified)
                SymbolDiscoveryOutcome.Qualified(
                    batch,
                    SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED))
                        .refined(),
                )
            else SymbolDiscoveryOutcome.Complete(batch)
        return IntellijDiscoveryPageMeasurements.from(outcome)
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

    private fun <V, E> Refinement<V, E>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private companion object {
        val fields =
            setOf(
                "examined_work_units",
                "inventory_files",
                "reacquired_files",
                "examined_leaves",
                "inventory_nanoseconds",
                "declaration_scan_nanoseconds",
                "projection_nanoseconds",
            )
    }
}
