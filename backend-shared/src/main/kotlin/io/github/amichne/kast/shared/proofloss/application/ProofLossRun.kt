package io.github.amichne.kast.shared.proofloss.application

import io.github.amichne.kast.shared.proofloss.analysis.ProofLossFinding
import io.github.amichne.kast.shared.proofloss.ir.ExtractionResult
import io.github.amichne.kast.shared.proofloss.ir.FunctionId

data class ProofLossRun(
    val analyzedFunctionIds: List<FunctionId>,
    val findings: List<ProofLossFinding>,
    val unsupported: List<ExtractionResult.Unsupported>,
)
