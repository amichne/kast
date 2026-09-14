package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IntellijSourcePageCollectorTest {
    private val snapshot = snapshot()
    private val parent = SourceSelector.issueRoot(range(0, 16), SourceRegionKind.FILE)
    private val selection = EntitySelection.matching(Containment.DIRECT, listOf(EntityFilter.Parameters)).proven()

    @Test
    fun `canceled production attempt publishes no page and retry keeps work without discarded facts`() {
        val execution = execution(work = 2)
        val retained = entity(2)
        val published = mutableListOf<NativeSourceEntityProjection>()
        val cancellation = CancellationException("Controlled read-attempt cancellation")
        var providerCalls = 0
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                published +=
                    collect(execution) { attempt ->
                        assertEquals(SourceExecutionAdmission.ADMITTED, attempt.admitUnit())
                        providerCalls += 1
                        attempt.offer(entity(1))
                        throw cancellation
                    }
            },
        )
        assertEquals(emptyList<NativeSourceEntityProjection>(), published)
        published +=
            collect(execution) { attempt ->
                assertEquals(SourceExecutionAdmission.ADMITTED, attempt.admitUnit())
                providerCalls += 1
                attempt.offer(retained)
                assertEquals(SourceExecutionAdmission.WORK_LIMIT_REACHED, attempt.admitUnit())
                NativeSourceEntityProjection.Projected(
                    attempt.finish().withLimitation(SourceReadLimitation.WORK_LIMIT_REACHED)
                )
            }
        val result = published.single() as NativeSourceEntityProjection.Projected
        assertEquals(listOf(retained), result.page.entities)
        assertEquals(setOf(SourceReadLimitation.WORK_LIMIT_REACHED), result.page.limitations)
        assertEquals(2, providerCalls)
    }

    @Test
    fun `canceled production attempt cannot renew elapsed allowance before another provider call`() {
        var now = 0L
        var providerCalls = 0
        val execution = execution(time = 2, clock = { now })
        assertThrows(CancellationException::class.java) {
            collect(execution) { attempt ->
                assertEquals(SourceExecutionAdmission.ADMITTED, attempt.admitUnit())
                providerCalls += 1
                attempt.offer(entity(1))
                now = 1_000_000L
                throw CancellationException("Controlled read-attempt cancellation")
            }
        }
        now = 2_000_000L
        val result =
            collect(execution) { attempt ->
                assertEquals(SourceExecutionAdmission.TIME_LIMIT_REACHED, attempt.admitUnit())
                NativeSourceEntityProjection.Projected(
                    attempt.finish().withLimitation(SourceReadLimitation.TIME_LIMIT_REACHED)
                )
            }
                as NativeSourceEntityProjection.Projected
        assertEquals(emptyList<SourceEntity>(), result.page.entities)
        assertEquals(setOf(SourceReadLimitation.TIME_LIMIT_REACHED), result.page.limitations)
        assertEquals(1, providerCalls)
    }

    @Test
    fun `excluded entities do not consume page capacity and eligible lookahead closes collection`() {
        val collector = collector()
        val excluded = entity(0, depth = 1)
        repeat(10_000) { assertEquals(SourceEntityCollectionAdmission.ACCEPTING, collector.offer(excluded)) }
        val first = entity(1)
        assertEquals(SourceEntityCollectionAdmission.ACCEPTING, collector.offer(first))
        assertEquals(SourceEntityCollectionAdmission.STOPPED, collector.offer(entity(2)))
        val page = collector.finish() as IntellijSourceEntityPage.Prefix
        assertEquals(listOf(first), page.entities)
        assertEquals(2, page.knownMinimumEntityCount)
        assertEquals(1, page.nextOrdinal)
        assertEquals(SourceEntityCollectionAdmission.STOPPED, collector.offer(entity(3)))
        assertEquals(page, collector.finish())
    }

    @Test
    fun `resumption drops the previous eligible prefix before retaining one page and lookahead`() {
        val collector = collector(start = 2, limit = 2)
        val entities = (0..4).map { entity(it) }
        entities.forEach { collector.offer(it) }
        val page = collector.finish() as IntellijSourceEntityPage.Prefix
        assertEquals(entities.subList(2, 4), page.entities)
        assertEquals(4, page.nextOrdinal)
        assertEquals(5, page.knownMinimumEntityCount)
    }

    @Test
    fun `ordered input proof includes excluded entities`() {
        val collector = collector()
        collector.offer(entity(3, depth = 1))
        assertEquals(SourceEntityCollectionAdmission.STOPPED, collector.offer(entity(1, depth = 1)))
        assertEquals(
            IntellijSourceReadRejection.CONTRACT_VIOLATION,
            (collector.finish() as IntellijSourceEntityPage.Rejected).reason,
        )
    }

    @Test
    fun `finished collection cannot accept later facts or change its completion evidence`() {
        val collector = collector()
        val complete = collector.finish() as IntellijSourceEntityPage.Complete
        assertEquals(emptyList<SourceEntity>(), complete.entities)
        assertEquals(SourceEntityCollectionAdmission.STOPPED, collector.offer(entity(1)))
        assertEquals(complete, collector.finish())
    }

    private fun collector(start: Int = 0, limit: Int = 1) =
        IntellijSourceEntityPageCollector(
            selection,
            IntellijSourceEntityCursor(start),
            SourceEntityLimit.parse(limit).proven(),
        )

    private fun collect(
        execution: IntellijSourceExecution,
        read: (IntellijSourceEntityAttempt) -> NativeSourceEntityProjection,
    ) =
        IntellijSourceEntityAttempt.collect(
            execution,
            selection,
            IntellijSourceEntityCursor(0),
            SourceEntityLimit.parse(10).proven(),
            read,
        )

    private fun execution(work: Long = 100, time: Long = 100, clock: () -> Long = { 0L }) =
        IntellijSourceExecution(
            ResourceBudget(
                ResultLimit.parse(10).proven(),
                WorkUnitLimit.parse(work).proven(),
                ElapsedTimeLimitMillis.parse(time).proven(),
            ),
            clock,
        )

    private fun entity(offset: Int, depth: Int = 0): SourceEntity {
        val selector =
            SourceSelector.issueEntity(
                    parent,
                    NonEmptySourceRange.create(range(offset, offset + 1)).proven(),
                    SourceEntityKind.VALUE_PARAMETER,
                    SourceEntityName.present("p$offset").proven(),
                )
                .proven()
        return SourceEntity.ValueParameter.create(selector, SourceNestingDepth.parse(depth).proven()).proven()
    }

    private fun range(start: Int, end: Int) =
        SourceRange.create(
                snapshot,
                Utf16CodeUnitOffset.parse(start).proven(),
                Utf16CodeUnitOffset.parse(end).proven(),
            )
            .proven()

    private fun snapshot(): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).proven()
        return SourceSnapshot.create(
            SemanticReadLease(root, EvidenceGeneration.parse(1).proven()),
            WorkspaceStateIdentity.parse("workspace-state-v1|source").proven(),
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        root,
                        Path.of("/workspace/Page.kt"),
                    )
                    .proven()
            ),
            SourceTextIdentity.fromNormalizedCommittedText("abcdefghijklmnop"),
            Utf16CodeUnitCount.parse(16).proven(),
        )
    }

    private fun <T> Refinement<T, *>.proven(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Page fixture rejected: $failure")
        }
}
