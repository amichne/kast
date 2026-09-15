package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.source.contract.SourceReadOutputIdentity

/** Retains the presentation choice solely for native continuation compatibility. */
internal fun SourceReadFormatDocument.domainOutputIdentity(): SourceReadOutputIdentity =
    when (this) {
        SourceReadFormatDocument.EXPANDED -> SourceReadOutputIdentity.EXPANDED
        SourceReadFormatDocument.COMPACT -> SourceReadOutputIdentity.COMPACT
    }
