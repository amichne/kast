package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement

/** Only schema-bounded cardinalities and unchanged refined text reach this extraction. */
internal fun <T> proven(value: Refinement<T, *>): T =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("A schema-admitted tool value violated its canonical bound")
    }
