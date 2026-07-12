package io.github.amichne.kast.shared.proofloss.application

import io.github.amichne.kast.shared.proofloss.analysis.ProofLossAnalyzer
import io.github.amichne.kast.shared.proofloss.analysis.ProofLossFinding
import io.github.amichne.kast.shared.proofloss.ir.ExtractionResult
import io.github.amichne.kast.shared.proofloss.ir.ProofFunctionId
import io.github.amichne.kast.shared.proofloss.ir.ProofIrExtractor
import io.github.amichne.kast.shared.proofloss.model.ProofModel

class ProofLossApplication<S>(
    model: ProofModel,
    private val extractor: ProofIrExtractor<S>,
) {
    private val analyzer = ProofLossAnalyzer(model)
    fun run(sources: Iterable<S>): ProofLossRun {
        val analyzed = mutableListOf<ProofFunctionId>()
        val findings = mutableListOf<ProofLossFinding>()
        val unsupported = mutableListOf<ExtractionResult.Unsupported>()
        sources.forEach { source ->
            when (val result = extractor.extract(source)) {
                is ExtractionResult.Supported -> {
                    analyzed += result.function.id; findings += analyzer.analyze(result.function)
                }
                is ExtractionResult.Unsupported -> unsupported += result
            }
        }
        return ProofLossRun(
            analyzed.sorted(),
            findings.sortedWith(compareBy({ it.functionId }, { it.boundaryCall.startOffset })),
            unsupported.sortedBy { it.functionId })
    }
}

data class ProofLossRun(
    val analyzedFunctionIds: List<ProofFunctionId>,
    val findings: List<ProofLossFinding>,
    val unsupported: List<ExtractionResult.Unsupported>,
)
