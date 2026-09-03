package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.EnclosingRegionKind
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.LineCount
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntellijSourceRegionReadTest {
    @Test
    fun `file candidate enters source port and establishes exact file authority`() {
        val text = "fun subject() = 1\n"
        val snapshot = snapshot(text)
        val candidate = CandidateSelector.restoreFile(snapshot.lease, snapshot.file)
        val fileSelector = SourceSelector.issueRoot(
            range(snapshot, 0, text.length),
            SourceRegionKind.FILE,
        )
        val port = IntellijSourceReadPort(
            IntellijSourceRegionAccess { _, request, _ ->
                assertEquals(candidate, (request.anchor as SourceReadAnchor.Candidate).selector)
                IntellijSourceRegionAccessResult.Selected(
                    IntellijSelectedSourceCapture.create(
                        snapshot,
                        fileSelector,
                        fileSelector,
                        text,
                    ).refined(),
                )
            },
        )

        val result = runSuspend { port.read(context(snapshot), request(candidate)) }
            as SourceReadResult.Complete

        assertEquals(SourceRegionKind.FILE, result.region.kind)
        assertEquals(text, (result.text as SourceTextProjection.Returned).text)
    }

    @Test
    fun `range candidate enters source port without acquiring symbol authority`() {
        val text = "fun subject() = 1\n"
        val snapshot = snapshot(text)
        val start = text.indexOf("subject")
        val end = start + "subject".length
        val candidate = CandidateSelector.restoreRange(
            snapshot.lease,
            snapshot.file,
            start,
            end,
        ).refined()
        val anchorSelector = SourceSelector.issueRoot(
            range(snapshot, start, end),
            SourceRegionKind.ANCHOR,
        )
        val port = IntellijSourceReadPort(
            IntellijSourceRegionAccess { _, request, _ ->
                assertEquals(candidate, (request.anchor as SourceReadAnchor.Candidate).selector)
                IntellijSourceRegionAccessResult.Selected(
                    IntellijSelectedSourceCapture.create(
                        snapshot,
                        anchorSelector,
                        anchorSelector,
                        text,
                    ).refined(),
                )
            },
        )

        val result = runSuspend { port.read(context(snapshot), request(candidate)) }
            as SourceReadResult.Complete

        assertEquals(SourceRegionKind.ANCHOR, result.region.kind)
        assertEquals("subject", (result.text as SourceTextProjection.Returned).text)
    }

    @Test
    fun `source selector re-entry preserves the exact region selector`() {
        val text = "fun subject() = 1\n"
        val snapshot = snapshot(text)
        val selector = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.DECLARATION)
        val port = port(
            IntellijSelectedSourceCapture.create(snapshot, selector, selector, text).refined(),
        )

        val result = runSuspend { port.read(context(snapshot), request(selector)) }
            as SourceReadResult.Complete
        val returned = result.text as SourceTextProjection.Returned

        assertEquals(selector.fingerprint, result.region.selector.fingerprint)
        assertEquals(selector.fingerprint, returned.selector.fingerprint)
        assertEquals(text, returned.text)
    }

    @Test
    fun `window is whole-line clipped to enclosing region and round trips`() {
        val text = "fun x() {\n  first()\n  target()\n  last()\n}\n"
        val snapshot = snapshot(text)
        val bodyStart = text.indexOf('{')
        val bodyEnd = text.lastIndexOf('}') + 1
        val body = SourceSelector.issueRoot(
            range(snapshot, bodyStart, bodyEnd),
            SourceRegionKind.CALLABLE_BODY,
        )
        val targetStart = text.indexOf("target")
        val targetRange = range(snapshot, targetStart, targetStart + "target".length)
        val target = SourceSelector.issueEntity(
            body,
            NonEmptySourceRange.create(targetRange).refined(),
            SourceEntityKind.REFERENCE,
            SourceEntityName.present("target").refined(),
        ).refined()
        val selected = IntellijSelectedSourceCapture.create(snapshot, target, body, text).refined()
        val port = port(selected)
        val request = request(
            target,
            region = RegionSelection.Enclosing(EnclosingRegionKind.CALLABLE_BODY),
            text = TextProjection.window(lineCount(1), lineCount(1)),
        )

        val result = runSuspend { port.read(context(snapshot), request) } as SourceReadResult.Complete
        val returned = result.text as SourceTextProjection.Returned

        assertEquals("  first()\n  target()\n  last()\n", returned.text)
        assertEquals(SourceRegionKind.WINDOW, (returned.selector as SourceSelector.NestedRegion).kind)
        assertTrue(returned.selector.range.startInclusive >= body.range.startInclusive)
        assertTrue(returned.selector.range.endExclusive <= body.range.endExclusive)

        val roundTrip = port(
            IntellijSelectedSourceCapture.create(
                snapshot,
                returned.selector,
                returned.selector,
                text,
            ).refined(),
        )
        val reread = runSuspend {
            roundTrip.read(context(snapshot), request(returned.selector))
        } as SourceReadResult.Complete
        assertEquals(returned.text, (reread.text as SourceTextProjection.Returned).text)
    }

    @Test
    fun `empty file is a complete exact source region`() {
        val snapshot = snapshot("")
        val file = SourceSelector.issueRoot(range(snapshot, 0, 0), SourceRegionKind.FILE)
        val result = runSuspend {
            port(IntellijSelectedSourceCapture.create(snapshot, file, file, "").refined())
                .read(context(snapshot), request(file))
        } as SourceReadResult.Complete

        assertEquals("", (result.text as SourceTextProjection.Returned).text)
        assertEquals(0, result.snapshot.length.value)
    }

    @Test
    fun `bodyless structural selection rejects rather than inventing empty success`() {
        val text = "expect fun subject()\n"
        val snapshot = snapshot(text)
        val selector = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.DECLARATION)
        val port = IntellijSourceReadPort(
            IntellijSourceRegionAccess { _, _, _ ->
                IntellijSourceRegionAccessResult.Rejected(IntellijSourceReadRejection.REGION_ABSENT)
            },
        )

        assertEquals(
            SourceReadResult.Rejected(SourceReadRejection.REGION_ABSENT),
            runSuspend {
                port.read(
                    context(snapshot),
                    request(selector, region = RegionSelection.Body(io.github.amichne.kast.source.contract.BodyKind.CALLABLE)),
                )
            },
        )
    }

    private fun port(capture: IntellijSelectedSourceCapture): IntellijSourceReadPort =
        IntellijSourceReadPort(
            IntellijSourceRegionAccess { _, _, _ ->
                IntellijSourceRegionAccessResult.Selected(capture)
            },
        )

    private fun request(
        selector: SourceSelector,
        region: RegionSelection = RegionSelection.Anchor,
        text: TextProjection = TextProjection.Complete,
    ): SourceReadRequest = request(SourceReadAnchor.Source(selector), region, text)

    private fun request(
        selector: CandidateSelector,
        region: RegionSelection = RegionSelection.Anchor,
        text: TextProjection = TextProjection.Complete,
    ): SourceReadRequest = request(SourceReadAnchor.Candidate(selector), region, text)

    private fun request(
        anchor: SourceReadAnchor,
        region: RegionSelection,
        text: TextProjection,
    ): SourceReadRequest = SourceReadRequest(
        anchor,
        region,
        EntitySelection.None,
        text,
        SourceEntityLimit.parse(250).refined(),
        SourceTextByteLimit.parse(65_536).refined(),
        SourceReadPage.First,
    )

    private fun snapshot(text: String): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/src/Subject.kt"),
        ).refined()
        return SourceSnapshot.create(
            SemanticReadLease(root, EvidenceGeneration.parse(42).refined()),
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            SymbolDiscoveryFileIdentity.Workspace(path),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
    }

    private fun context(snapshot: SourceSnapshot): SourceReadContext =
        SourceReadContext(snapshot.lease, snapshot.sourceState)

    private fun range(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange =
        SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(start).refined(),
            Utf16CodeUnitOffset.parse(end).refined(),
        ).refined()

    private fun lineCount(raw: Int): LineCount = LineCount.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var completion: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Value>) {
                    completion = result
                }
            },
        )
        return checkNotNull(completion).getOrThrow()
    }
}
