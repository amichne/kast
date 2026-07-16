package io.github.amichne.kast.api.contract.selector

@JvmInline
value class SelectorHandle private constructor(val value: String) {
    companion object {
        const val PREFIX: String = "ksh1."
        private const val MAX_LENGTH: Int = 4_096

        fun parse(value: String): SelectorHandle {
            require(value.length in (PREFIX.length + 1)..MAX_LENGTH) {
                "Selector handle length is invalid"
            }
            require(value.isAsciiWithoutControls()) {
                "Selector handle must contain printable ASCII only"
            }
            require(value.startsWith(PREFIX)) {
                "Selector handle has an unsupported version"
            }
            return SelectorHandle(value)
        }

        private fun String.isAsciiWithoutControls(): Boolean =
            all { character -> character.code in 0x20..0x7e }
    }
}
