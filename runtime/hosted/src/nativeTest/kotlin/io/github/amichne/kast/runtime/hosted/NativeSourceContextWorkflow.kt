package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.SourceProtocolBudget
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal enum class SourceContextStrategy {
    FILE_RECONSTRUCTION,
    STRUCTURAL_VIEW,
}

/** A matched protocol comparison, with no model, socket, or installed-host claim. */
internal class NativeSourceContextWorkflow(
    private val fixture: NativeScopedTraceFixture,
    private val request: QueryRunRequest.Run,
    private val budget: QueryBudget,
    private val policy: QueryInvocationPolicy,
    private val expectedOffset: Int,
    private val expectedBody: String,
) {
    suspend fun run(strategy: SourceContextStrategy): SourceContextCost {
        val counters = fixture.observation.snapshot()
        val started = System.nanoTime()
        val outcome =
            fixture.protocol().execute(request = request, lease = fixture.lease, budget = budget, policy = policy)
        assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        val rows = result.items.values.map { it as QueryResultItemDocument.ExactSymbol }
        assertEquals(2, rows.size)
        assertTrue(rows.all { checkNotNull(it.location).range.startInclusive.value == expectedOffset })
        val focus = rows.map { it.ref.token }.distinct().single()
        val prefix = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        val reads = sourceViews(strategy, focus)
        when (strategy) {
            SourceContextStrategy.FILE_RECONSTRUCTION ->
                assertTrue(
                    returned(reads.last().result).text.value.contains("fun target(value: Int): Int $expectedBody")
                )
            SourceContextStrategy.STRUCTURAL_VIEW ->
                assertEquals(expectedBody, returned(reads.last().result).text.value)
        }
        return SourceContextCost(
            calls = 1 + reads.size,
            sourceReads = reads.size,
            bytes = prefix.document.value.toByteArray().size + reads.sumOf { it.bytes },
            sourceBytes = reads.sumOf { it.sourceBytes },
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            nativeCounters =
                fixture.observation
                    .snapshot()
                    .mapValues { (counter, value) -> value - (counters[counter] ?: 0L) }
                    .filterValues { it > 0 },
        )
    }

    private suspend fun sourceViews(
        strategy: SourceContextStrategy,
        focus: io.github.amichne.kast.protocol.contract.ProtocolText,
    ): List<MeasuredSource> =
        when (strategy) {
            SourceContextStrategy.FILE_RECONSTRUCTION ->
                listOf(
                    measuredRead(
                        sourceRequest(
                            SourceReadAnchorDocument.SnapshotSymbol(focus),
                            SourceRegionSelectionDocument.File,
                        )
                    )
                )
            SourceContextStrategy.STRUCTURAL_VIEW -> {
                val initial = measuredRead(sourceRequest(SourceReadAnchorDocument.SnapshotSymbol(focus), body, excerpt))
                assertEquals("{\n        val adjusted = value + 1\n", returned(initial.result).text.value)
                val expanded =
                    measuredRead(
                        sourceRequest(SourceReadAnchorDocument.Source(initial.result.region.selection.selector), anchor)
                    )
                listOf(initial, expanded)
            }
        }

    private suspend fun measuredRead(request: SourceReadRequest): MeasuredSource {
        val outcome = fixture.sourceProtocol().execute(request, fixture.lease, sourceBudget)
        assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        val encoded = CanonicalSourceReadCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        return MeasuredSource(
            result,
            encoded.document.value.toByteArray().size,
            returned(result).text.value.toByteArray().size,
        )
    }

    private fun returned(result: SourceReadResult) = result.text as SourceTextProjectionDocument.Returned

    private fun sourceRequest(
        focus: SourceReadAnchorDocument,
        region: SourceRegionSelectionDocument,
        text: SourceTextRequestDocument = SourceTextRequestDocument.Complete,
    ) = SourceReadRequest(anchor = focus, region = region, entities = SourceEntitySelectionDocument.None, text = text)

    private val body = SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
    private val anchor = SourceRegionSelectionDocument.Anchor
    private val excerpt = SourceTextRequestDocument.Excerpt(SourceLineCountDocument.parse(2).traceRefined())
    private val sourceBudget =
        SourceProtocolBudget(NativeScopedTraceFixture.resources, SourceTextByteLimit.parse(65536).traceRefined())
}

private data class MeasuredSource(val result: SourceReadResult, val bytes: Int, val sourceBytes: Int)

internal data class SourceContextCost(
    val calls: Int,
    val sourceReads: Int,
    val bytes: Int,
    val sourceBytes: Int,
    val elapsedMillis: Long,
    val nativeCounters: Map<IntellijReadCounter, Long>,
)
