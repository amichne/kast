package io.github.amichne.kast.shared.proofloss.model

@JvmInline
value class ArgumentIndex private constructor(val value: Int) : Comparable<ArgumentIndex> {
    override fun compareTo(other: ArgumentIndex): Int = value.compareTo(other.value)

    companion object {
        fun parse(raw: Int): ArgumentIndexParseResult =
            if (raw >= 0) ArgumentIndexParseResult.Valid(ArgumentIndex(raw))
            else ArgumentIndexParseResult.Negative(raw)
    }
}
