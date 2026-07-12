package io.github.amichne.kast.shared.proofloss.model

@JvmInline
value class KotlinTypeKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<KotlinTypeKey> = parseText(raw, ::KotlinTypeKey)
    }
}
