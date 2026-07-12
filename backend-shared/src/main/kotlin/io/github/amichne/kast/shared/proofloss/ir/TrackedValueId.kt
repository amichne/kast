package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.api.contract.NormalizedPath

data class TrackedValueId(
    val filePath: NormalizedPath,
    val declarationOffset: SourceOffset,
)
