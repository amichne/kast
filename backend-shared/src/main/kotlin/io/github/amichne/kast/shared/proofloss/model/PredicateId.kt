package io.github.amichne.kast.shared.proofloss.model

@JvmInline
value class PredicateId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<PredicateId> = parseText(raw, ::PredicateId)
    }
}
