package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.api.contract.NormalizedPath

data class FunctionId(
    val filePath: NormalizedPath,
    val declarationOffset: SourceOffset,
) : Comparable<FunctionId> {
    override fun compareTo(other: FunctionId): Int =
        compareValuesBy(this, other, { it.filePath }, { it.declarationOffset })
}
