package io.github.amichne.kast.relation.contract

/** Only the scan supplies traversal admission and reference confirmation under its original allowance. */
class LocalReferenceTraversal<C>
internal constructor(
    private val admit: () -> Boolean,
    private val visit: (C) -> Boolean,
) {
    fun admitTraversal(): Boolean = admit()

    operator fun invoke(candidate: C): Boolean = visit(candidate)
}
