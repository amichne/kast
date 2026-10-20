package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite

/** Keep space for each finite refusal under the conservative detached estimate. */
internal fun reservedValueObligationBytes(source: ValueSite): Long {
    val count = ValueFlowUnsupportedCause.entries.size.toLong()
    val bytes = source.retainedBytes
    return if (bytes > Long.MAX_VALUE / count - VALUE_OBLIGATION_OVERHEAD_BYTES) Long.MAX_VALUE
    else (bytes + VALUE_OBLIGATION_OVERHEAD_BYTES) * count
}

private const val VALUE_OBLIGATION_OVERHEAD_BYTES = 2048L
