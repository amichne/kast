package io.github.amichne.kast.source.intellij

import com.intellij.openapi.util.TextRange
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset

internal fun SourceSelector.nativeRegionKind(): SourceRegionKind =
    when (this) {
        is SourceSelector.RootRegion -> kind
        is SourceSelector.NestedRegion -> kind
        is SourceSelector.Entity -> SourceRegionKind.ANCHOR
    }

internal fun SourceSnapshot.sourceRange(textRange: TextRange): SourceRange? =
    sourceRange(textRange.startOffset, textRange.endOffset)

internal fun SourceSnapshot.sourceRange(start: Int, end: Int): SourceRange? {
    val startOffset =
        when (val parsed = Utf16CodeUnitOffset.parse(start)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
    val endOffset =
        when (val parsed = Utf16CodeUnitOffset.parse(end)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
    return when (val admitted = SourceRange.create(this, startOffset, endOffset)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> null
    }
}

internal fun issueRegionSelector(
    anchor: SourceSelector,
    range: SourceRange,
    kind: SourceRegionKind,
): SourceSelector? {
    val sameRange =
        range.startInclusive == anchor.range.startInclusive && range.endExclusive == anchor.range.endExclusive
    if (sameRange && anchor.nativeRegionKind() == kind && anchor !is SourceSelector.Entity) {
        return anchor
    }
    val insideAnchor =
        range.startInclusive >= anchor.range.startInclusive && range.endExclusive <= anchor.range.endExclusive
    return if (insideAnchor) {
        when (val issued = SourceSelector.issueNested(anchor, range, kind)) {
            is Refinement.Refined -> issued.value
            is Refinement.Rejected -> null
        }
    } else {
        SourceSelector.issueRoot(range, kind)
    }
}
