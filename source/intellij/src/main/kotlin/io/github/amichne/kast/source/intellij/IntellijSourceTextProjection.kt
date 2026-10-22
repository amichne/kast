package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset

/** Text framing keeps the selected structural region separate from its returned window. */
internal fun IntellijSelectedSourceCapture.projectText(
    projection: TextProjection
): Refinement<SourceTextProjection, SourceReadRejection> =
    when (projection) {
        TextProjection.None -> Refinement.Refined(SourceTextProjection.NotRequested)
        TextProjection.Complete -> returnedText(regionSelector)
        is TextProjection.Window -> boundedText(windowRange(projection))
        is TextProjection.Excerpt -> boundedText(excerptRange(projection))
    }

private fun IntellijSelectedSourceCapture.boundedText(
    range: Refinement<SourceRange, SourceReadRejection>
): Refinement<SourceTextProjection.Returned, SourceReadRejection> =
    when (range) {
        is Refinement.Rejected -> range
        is Refinement.Refined ->
            when (val issued = SourceSelector.issueNested(regionSelector, range.value, SourceRegionKind.WINDOW)) {
                is Refinement.Rejected -> Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
                is Refinement.Refined -> returnedText(issued.value)
            }
    }

private fun IntellijSelectedSourceCapture.returnedText(
    selector: SourceSelector
): Refinement<SourceTextProjection.Returned, SourceReadRejection> =
    when (val projected = SourceTextProjection.returned(selector, normalizedDocumentText)) {
        is Refinement.Refined -> projected
        is Refinement.Rejected -> Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    }

private fun IntellijSelectedSourceCapture.windowRange(
    projection: TextProjection.Window
): Refinement<SourceRange, SourceReadRejection> {
    val regionStart = regionSelector.range.startInclusive.value
    val regionEnd = regionSelector.range.endExclusive.value
    val seedStart = maxOf(anchorSelector.range.startInclusive.value, regionStart)
    val seedEnd = minOf(anchorSelector.range.endExclusive.value, regionEnd)
    if (seedStart > seedEnd) return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    var start = lineStart(normalizedDocumentText, seedStart).coerceAtLeast(regionStart)
    repeat(projection.beforeLines.value) {
        if (start > regionStart) {
            start = lineStart(normalizedDocumentText, start - 1).coerceAtLeast(regionStart)
        }
    }
    var end = lineEnd(normalizedDocumentText, seedEnd).coerceAtMost(regionEnd)
    repeat(projection.afterLines.value) {
        if (end < regionEnd) {
            end = lineEnd(normalizedDocumentText, (end + 1).coerceAtMost(regionEnd)).coerceAtMost(regionEnd)
        }
    }
    return sourceRange(start, end)
}

private fun IntellijSelectedSourceCapture.excerptRange(
    projection: TextProjection.Excerpt
): Refinement<SourceRange, SourceReadRejection> {
    val regionStart = regionSelector.range.startInclusive.value
    val regionEnd = regionSelector.range.endExclusive.value
    val focus = anchorSelector.range.startInclusive.value.coerceIn(regionStart, regionEnd)
    var start = lineStart(normalizedDocumentText, focus).coerceAtLeast(regionStart)
    repeat(projection.maxLines.value / 2) {
        if (start > regionStart) {
            start = lineStart(normalizedDocumentText, start - 1).coerceAtLeast(regionStart)
        }
    }
    var end = start
    repeat(projection.maxLines.value) {
        if (end < regionEnd) {
            val newline = normalizedDocumentText.indexOf('\n', end)
            end = if (newline < 0) regionEnd else (newline + 1).coerceAtMost(regionEnd)
        }
    }
    return sourceRange(start, end)
}

private fun IntellijSelectedSourceCapture.sourceRange(
    start: Int,
    end: Int,
): Refinement<SourceRange, SourceReadRejection> {
    val startOffset =
        when (val parsed = Utf16CodeUnitOffset.parse(start)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
    val endOffset =
        when (val parsed = Utf16CodeUnitOffset.parse(end)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
    return when (val admitted = SourceRange.create(snapshot, startOffset, endOffset)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    }
}

private fun lineStart(text: String, offset: Int): Int {
    val searchFrom = (offset - 1).coerceAtMost(text.lastIndex)
    return if (searchFrom < 0) 0 else text.lastIndexOf('\n', searchFrom) + 1
}

private fun lineEnd(text: String, offset: Int): Int {
    if (offset <= 0 && text.isEmpty()) return 0
    if (offset > 0 && offset <= text.length && text[offset - 1] == '\n') return offset
    val newline = text.indexOf('\n', offset.coerceAtMost(text.length))
    return if (newline < 0) text.length else newline + 1
}
