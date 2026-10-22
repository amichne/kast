package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.source.contract.BodyKind
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.LineCount
import io.github.amichne.kast.source.contract.LineCountFailure
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceExcerptFailure
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.SourceTextWithheldReason
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** These cases exercise text framing after structural selection, with no compiler or filesystem effect. */
class IntellijSourceExcerptReadTest {
    @Test
    fun `a declaration spanning the whole body still returns at most the selected line count`() = runTest {
        val text = "fun owner() {\n" + (1..20).joinToString("\n") { "  step$it()" } + "\n}\n"
        val fixture = capture(text, 0, text.length - 1)
        val result = read(fixture, excerpt(4)) as SourceReadResult.Complete
        val returned = result.text as SourceTextProjection.Returned
        assertEquals("{\n  step1()\n  step2()\n  step3()\n", returned.text)
        assertEquals(SourceRegionKind.WINDOW, (returned.selector as SourceSelector.NestedRegion).kind)
        assertEquals(SourceRegionKind.CALLABLE_BODY, result.region.kind)
        assertEquals(fixture.regionSelector.fingerprint, result.region.selector.fingerprint)
        assertEquals(4, returned.lines.endInclusive.value - returned.lines.startInclusive.value + 1)
        assertTrue(returned.selector.range.endExclusive < result.region.selector.range.endExclusive)

        val body =
            IntellijSelectedSourceCapture.create(
                    fixture.snapshot,
                    result.region.selector,
                    result.region.selector,
                    text,
                )
                .refined()
        val expanded = read(body, TextProjection.Complete) as SourceReadResult.Complete
        assertEquals(
            text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1),
            (expanded.text as SourceTextProjection.Returned).text,
        )
    }

    @Test
    fun `an occurrence excerpt centers its line budget on the focus`() = runTest {
        val text = "fun owner() {\n" + (1..20).joinToString("\n") { "  step$it()" } + "\n}\n"
        val start = text.indexOf("step17")
        val result = read(capture(text, start, start + "step17".length), excerpt(5)) as SourceReadResult.Complete
        val returned = result.text as SourceTextProjection.Returned
        assertEquals("  step15()\n  step16()\n  step17()\n  step18()\n  step19()\n", returned.text)
        assertEquals(5, returned.lines.endInclusive.value - returned.lines.startInclusive.value + 1)
    }

    @Test
    fun `an oversized UTF-8 line withholds text and keeps the structural region`() = runTest {
        val text = "fun owner() { " + "λ".repeat(20) + " }\n"
        val fixture = capture(text, 0, text.length - 1)
        val result = read(fixture, excerpt(1), 10) as SourceReadResult.Qualified
        assertEquals(SourceTextProjection.Withheld(SourceTextWithheldReason.BYTE_LIMIT_REACHED), result.text)
        assertEquals(listOf(SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED), result.qualification.limitations)
        assertEquals(fixture.regionSelector.fingerprint, result.region.selector.fingerprint)
    }

    @Test
    fun `zero lines cannot construct an excerpt while zero context remains a legal window`() {
        val zero = LineCount.parse(0).refined()
        assertEquals(Refinement.Rejected(SourceExcerptFailure.ZERO_LINES), TextProjection.Excerpt.create(zero))
        assertEquals(0, TextProjection.window(zero, zero).beforeLines.value)
        assertTrue(TextProjection.Excerpt.create(LineCount.parse(1).refined()) is Refinement.Refined)
        assertEquals(Refinement.Rejected(LineCountFailure.TOO_LARGE), LineCount.parse(1001))
    }

    @Test
    fun `blank lines consume the same excerpt budget as nonempty lines`() = runTest {
        val text = "fun owner() {\n\n\n  step()\n}\n"
        val result = read(capture(text, 0, text.length - 1), excerpt(3)) as SourceReadResult.Complete
        assertEquals("{\n\n\n", (result.text as SourceTextProjection.Returned).text)
    }

    private suspend fun read(
        capture: IntellijSelectedSourceCapture,
        text: TextProjection,
        bytes: Long = 65536,
    ): SourceReadResult {
        val port =
            IntellijSourceReadPort(
                IntellijSourceRegionAccess { _, _, _ ->
                    IntellijSourceRegionAccessResult.Selected(capture)
                }
            )
        return port.read(
            capture.snapshot.context,
            SourceReadRequest(
                SourceReadAnchor.Source(capture.anchorSelector),
                RegionSelection.Body(BodyKind.CALLABLE),
                EntitySelection.None,
                text,
                SourceEntityLimit.parse(1).refined(),
                SourceTextByteLimit.parse(bytes).refined(),
                SourceReadPage.First,
                ResourceBudget(
                    ResultLimit.parse(10).refined(),
                    WorkUnitLimit.parse(100).refined(),
                    ElapsedTimeLimitMillis.parse(2000).refined(),
                ),
            ),
        )
    }

    private fun capture(text: String, start: Int, end: Int): IntellijSelectedSourceCapture {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/Owner.kt")).refined()
            )
        val snapshot =
            SourceSnapshot.create(
                SemanticReadLease(root, EvidenceGeneration.parse(7).refined()),
                WorkspaceStateIdentity.parse("state").refined(),
                file,
                SourceTextIdentity.fromNormalizedCommittedText(text),
                Utf16CodeUnitCount.parse(text.length).refined(),
            )
        fun range(first: Int, last: Int) =
            SourceRange.create(
                    snapshot,
                    Utf16CodeUnitOffset.parse(first).refined(),
                    Utf16CodeUnitOffset.parse(last).refined(),
                )
                .refined()
        val anchor = SourceSelector.issueRoot(range(start, end), SourceRegionKind.ANCHOR)
        val body =
            SourceSelector.issueRoot(
                range(text.indexOf('{'), text.lastIndexOf('}') + 1),
                SourceRegionKind.CALLABLE_BODY,
            )
        return IntellijSelectedSourceCapture.create(snapshot, anchor, body, text).refined()
    }

    private fun excerpt(lines: Int) = TextProjection.Excerpt.create(LineCount.parse(lines).refined()).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected $failure")
        }
}
