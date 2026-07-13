package io.github.amichne.kast.shared.proofloss.model

@JvmInline
value class BoundaryId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<BoundaryId> = parseText(raw, ::BoundaryId)
    }
}
