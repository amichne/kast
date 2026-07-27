package io.github.amichne.kast.shared.proofloss.ir

fun interface IrExtractor<S> {
    fun extract(source: S): ExtractionResult
}
