package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.api.contract.NonEmptyList

sealed interface ExtractionResult {
    data class Supported(val function: FunctionIr) : ExtractionResult

    data class Unsupported(
        val functionId: FunctionId,
        val reasons: NonEmptyList<UnsupportedReason>,
    ) : ExtractionResult
}
