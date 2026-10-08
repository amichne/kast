package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

private const val VALUE_RECORD_OVERHEAD = 2048L

internal fun valueSiteStorageBytes(site: ValueSite): Long =
    VALUE_RECORD_OVERHEAD.addBytes(site.enclosing.detachedTextUnits().multiplyBytes(2))
        .addBytes(
            when (val role = site.role) {
                is ValueRole.Argument ->
                    VALUE_RECORD_OVERHEAD.addBytes(role.call.callable.detachedTextUnits().multiplyBytes(2))
                ValueRole.ExpressionResult,
                ValueRole.LocalBinding,
                ValueRole.LocalRead,
                ValueRole.Return,
                ValueRole.PropertyAssignment -> 0L
            }
        )

internal fun valueFlowStorageBytes(
    source: ValueSite,
    domain: RelationRequest,
    transfers: List<ValueTransfer>,
    obligations: List<ValueFlowObligation>,
): RelationByteCount {
    val bytes =
        VALUE_RECORD_OVERHEAD.addBytes(source.retainedBytes)
            .addBytes(domain.subject.detachedTextUnits().multiplyBytes(2))
            .addBytes(domain.boundary.canonical(domain.subject).length.toLong().multiplyBytes(2))
            .addBytes(
                transfers.fold(0L) { sum, edge ->
                    sum.addBytes(VALUE_RECORD_OVERHEAD)
                        .addBytes(edge.evidence.retainedBytes)
                        .addBytes(edge.source.retainedBytes)
                        .addBytes(edge.target.retainedBytes)
                }
            )
            .addBytes(
                obligations.fold(0L) { sum, obligation ->
                    sum.addBytes(VALUE_RECORD_OVERHEAD).addBytes(obligation.site.retainedBytes)
                }
            )
    return when (val count = RelationByteCount.parse(bytes)) {
        is Refinement.Refined -> count.value
        is Refinement.Rejected -> error("Saturating detached byte count cannot be negative")
    }
}

internal fun Long.addBytes(other: Long): Long = if (other > Long.MAX_VALUE - this) Long.MAX_VALUE else this + other

internal fun Long.multiplyBytes(other: Long): Long = if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
