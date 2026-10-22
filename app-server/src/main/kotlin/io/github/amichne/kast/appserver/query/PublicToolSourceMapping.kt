package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument

/** Public source choices reuse the canonical structural and text owners. */
internal fun PublicToolRegion?.lowerSourceRegion(): SourceRegionSelectionDocument =
    when (this) {
        null,
        PublicToolRegion.ANCHOR -> SourceRegionSelectionDocument.Anchor
        PublicToolRegion.CALLABLE_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
        PublicToolRegion.CLASS_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS)
        PublicToolRegion.FILE -> SourceRegionSelectionDocument.File
        PublicToolRegion.ENCLOSING_DECLARATION ->
            SourceRegionSelectionDocument.Enclosing(SourceEnclosingRegionKindDocument.DECLARATION)
        PublicToolRegion.ENCLOSING_CALLABLE_BODY ->
            SourceRegionSelectionDocument.Enclosing(SourceEnclosingRegionKindDocument.CALLABLE_BODY)
        PublicToolRegion.ENCLOSING_CLASS_BODY ->
            SourceRegionSelectionDocument.Enclosing(SourceEnclosingRegionKindDocument.CLASS_BODY)
    }

internal fun PublicToolSourceText?.lowerSourceText(): SourceTextRequestDocument =
    when (this) {
        null,
        PublicToolSourceTextComplete -> SourceTextRequestDocument.Complete
        PublicToolSourceTextNone -> SourceTextRequestDocument.None
        is PublicToolSourceTextWindow ->
            SourceTextRequestDocument.Window(
                proven(SourceLineCountDocument.parse(beforeLines ?: PublicToolDefaults.sourceBeforeLines)),
                proven(SourceLineCountDocument.parse(afterLines ?: PublicToolDefaults.sourceAfterLines)),
            )
    }
