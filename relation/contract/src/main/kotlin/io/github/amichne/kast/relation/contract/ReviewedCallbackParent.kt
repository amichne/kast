package io.github.amichne.kast.relation.contract

/** A reviewed callback returns to one bounded source owner, independently of its invocation evidence. */
sealed interface ReviewedCallbackParent {
    data object Declaration : ReviewedCallbackParent

    data class DeclaredCallback internal constructor(val binding: DeclaredCallbackValueBinding) : ReviewedCallbackParent

    val retainedBytes: Long
        get() =
            when (this) {
                Declaration -> 0L
                is DeclaredCallback -> binding.retainedBytes
            }
}
