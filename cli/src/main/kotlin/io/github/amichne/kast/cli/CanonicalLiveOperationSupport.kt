package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** Finite hosted operations whose process schemas retain live authority or pre-admission failures. */
internal fun CanonicalOperation.supportsLiveEvidence(): Boolean =
    when (this) {
        CanonicalOperation.QUERY_RUN,
        CanonicalOperation.SYMBOL_DISCOVER,
        CanonicalOperation.SYMBOL_INSPECT,
        CanonicalOperation.SOURCE_READ,
        CanonicalOperation.RELATION_READ,
        CanonicalOperation.TRAVERSAL_RUN,
        CanonicalOperation.DIAGNOSTIC_CHECK,
        CanonicalOperation.CHANGE_PLAN,
        CanonicalOperation.CHANGE_APPLY,
        CanonicalOperation.CHANGE_RECOVER -> true
        CanonicalOperation.INDEX_SYNC,
        CanonicalOperation.TOPOLOGY_BUILD -> false
    }
