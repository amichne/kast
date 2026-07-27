package io.github.amichne.kast.shared.proofloss.model

@JvmInline
value class CallableIdKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<CallableIdKey> = parseText(raw, ::CallableIdKey)
    }
}
