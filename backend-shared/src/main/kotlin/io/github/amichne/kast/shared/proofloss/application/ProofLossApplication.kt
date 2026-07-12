package io.github.amichne.kast.shared.proofloss.application

import io.github.amichne.kast.shared.proofloss.analysis.ProofLossAnalyzer
import io.github.amichne.kast.shared.proofloss.ir.ExtractionResult
import io.github.amichne.kast.shared.proofloss.ir.IrExtractor
import io.github.amichne.kast.shared.proofloss.model.ProofModel

class ProofLossApplication<S>(
    model: ProofModel,
    private val extractor: IrExtractor<S>,
) {
    private val analyzer = ProofLossAnalyzer(model)

    fun run(sources: Iterable<S>): ProofLossRun = sources.map(extractor::extract).let { results ->
        val supported = results.filterIsInstance<ExtractionResult.Supported>()
        ProofLossRun(
            supported.map { it.function.id }.sorted(),
            supported
                .flatMap { analyzer.analyze(it.function) }
                .sortedWith(compareBy({ it.functionId }, { it.boundaryCall.startOffset })),
            results.filterIsInstance<ExtractionResult.Unsupported>().sortedBy { it.functionId },
        )
    }
}
