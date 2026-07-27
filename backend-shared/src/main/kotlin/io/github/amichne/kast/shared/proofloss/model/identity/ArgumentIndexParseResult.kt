package io.github.amichne.kast.shared.proofloss.model

sealed interface ArgumentIndexParseResult {
    data class Valid(val value: ArgumentIndex) : ArgumentIndexParseResult
    data class Negative(val value: Int) : ArgumentIndexParseResult
}
