package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SourceContinuationRetentionTest {
    @Test
    fun `changing output format rejects a native continuation`() {
        val owner = IntellijSourceReadContinuations()
        val fixture = fixture()
        val token = owner.issue(fixture.request, fixture.capture, 1).refined()
        assertEquals(
            IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.REQUEST_MISMATCH),
            owner.admit(
                fixture.capture.snapshot.context,
                fixture.request.copy(
                    page = SourceReadPage.Continue(token),
                    outputIdentity = io.github.amichne.kast.source.contract.SourceReadOutputIdentity.COMPACT,
                ),
            ),
        )
    }

    @Test
    fun `replay does not renew expiry and expired continuation cannot restore`() {
        var now = 0L
        val owner =
            IntellijSourceReadContinuations(limits(ReadLimitParameter.SOURCE_CONTINUATION_TTL_MILLIS, 10)) { now }
        val fixture = fixture()
        val token = owner.issue(fixture.request, fixture.capture, 1).refined()
        val resumed = fixture.request.copy(page = SourceReadPage.Continue(token))
        now = TimeUnit.MILLISECONDS.toNanos(6)
        assertEquals(token, owner.issue(fixture.request, fixture.capture, 1).refined())
        repeat(2) {
            assertInstanceOf(
                IntellijSourceContinuationAdmission.Admitted::class.java,
                owner.admit(fixture.capture.snapshot.context, resumed),
            )
        }
        now = 9_999_999L
        assertEquals(token, owner.issue(fixture.request, fixture.capture, 1).refined())
        assertInstanceOf(
            IntellijSourceContinuationAdmission.Admitted::class.java,
            owner.admit(fixture.capture.snapshot.context, resumed),
        )
        for (age in listOf(10_000_000L, 10_000_001L)) {
            now = age
            assertEquals(
                IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.UNAVAILABLE),
                owner.admit(fixture.capture.snapshot.context, resumed),
            )
        }
    }

    @Test
    fun `indivisible checkpoint beyond retained byte capacity is rejected`() {
        val owner = IntellijSourceReadContinuations(limits(ReadLimitParameter.SOURCE_CONTINUATION_BYTES, 1))
        val fixture = fixture()
        assertInstanceOf(Refinement.Rejected::class.java, owner.issue(fixture.request, fixture.capture, 1))
    }

    @Test
    fun `entry eviction preserves newest checkpoint and replay remains nonconsuming`() {
        val owner = IntellijSourceReadContinuations(limits(ReadLimitParameter.SOURCE_CONTINUATIONS, 1))
        val fixture = fixture()
        val first = owner.issue(fixture.request, fixture.capture, 1).refined()
        val second = owner.issue(fixture.request, fixture.capture, 2).refined()
        assertEquals(
            IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.UNAVAILABLE),
            owner.admit(fixture.capture.snapshot.context, fixture.request.copy(page = SourceReadPage.Continue(first))),
        )
        repeat(2) {
            val admitted =
                assertInstanceOf(
                    IntellijSourceContinuationAdmission.Admitted::class.java,
                    owner.admit(
                        fixture.capture.snapshot.context,
                        fixture.request.copy(page = SourceReadPage.Continue(second)),
                    ),
                )
            assertEquals(2, admitted.cursor.startOrdinal)
        }
    }

    @Test
    fun `native eviction retains recently replayed checkpoint instead of insertion order`() {
        val owner = IntellijSourceReadContinuations(limits(ReadLimitParameter.SOURCE_CONTINUATIONS, 2)) { 0L }
        val fixture = fixture()
        val first = owner.issue(fixture.request, fixture.capture, 1).refined()
        val second = owner.issue(fixture.request, fixture.capture, 2).refined()
        assertInstanceOf(
            IntellijSourceContinuationAdmission.Admitted::class.java,
            owner.admit(fixture.capture.snapshot.context, fixture.request.copy(page = SourceReadPage.Continue(first))),
        )
        val third = owner.issue(fixture.request, fixture.capture, 3).refined()
        assertEquals(
            IntellijSourceContinuationAdmission.Rejected(IntellijSourceContinuationRejection.UNAVAILABLE),
            owner.admit(fixture.capture.snapshot.context, fixture.request.copy(page = SourceReadPage.Continue(second))),
        )
        for (token in listOf(first, third)) {
            assertInstanceOf(
                IntellijSourceContinuationAdmission.Admitted::class.java,
                owner.admit(
                    fixture.capture.snapshot.context,
                    fixture.request.copy(page = SourceReadPage.Continue(token)),
                ),
            )
        }
    }

    private fun limits(parameter: ReadLimitParameter, value: Int): ReadLimits =
        ReadLimits.resolve(environment = mapOf(parameter.environmentKey to value.toString())).refined()

    private fun fixture(): Fixture {
        val text = "class Subject"
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val file = CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/Subject.kt")).refined()
        val snapshot =
            SourceSnapshot.create(
                SemanticReadLease(root, EvidenceGeneration.parse(42).refined()),
                WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
                SymbolDiscoveryFileIdentity.Workspace(file),
                SourceTextIdentity.fromNormalizedCommittedText(text),
                Utf16CodeUnitCount.parse(text.length).refined(),
            )
        val range =
            SourceRange.create(
                    snapshot,
                    Utf16CodeUnitOffset.parse(0).refined(),
                    Utf16CodeUnitOffset.parse(text.length).refined(),
                )
                .refined()
        val selector = SourceSelector.issueRoot(range, SourceRegionKind.FILE)
        return Fixture(
            SourceReadRequest(
                SourceReadAnchor.Source(selector),
                RegionSelection.Anchor,
                EntitySelection.None,
                TextProjection.None,
                SourceEntityLimit.parse(1).refined(),
                SourceTextByteLimit.parse(1000).refined(),
                SourceReadPage.First,
                resources = sourceTestResources(),
            ),
            IntellijSelectedSourceCapture.create(snapshot, selector, selector, text).refined(),
        )
    }

    private data class Fixture(val request: SourceReadRequest, val capture: IntellijSelectedSourceCapture)

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}

private fun sourceTestResources(): io.github.amichne.kast.kernel.ResourceBudget =
    io.github.amichne.kast.kernel.ResourceBudget(
        (io.github.amichne.kast.kernel.ResultLimit.parse(1000) as Refinement.Refined).value,
        (io.github.amichne.kast.kernel.WorkUnitLimit.parse(10000) as Refinement.Refined).value,
        (io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(2000) as Refinement.Refined).value,
    )
