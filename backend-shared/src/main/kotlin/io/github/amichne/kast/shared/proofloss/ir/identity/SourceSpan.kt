package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.api.contract.NormalizedPath

data class SourceSpan(
    val filePath: NormalizedPath,
    val startOffset: SourceOffset,
    val endOffset: SourceOffset,
) {
    init {
        require(endOffset >= startOffset)
    }
}
