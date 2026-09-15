package io.github.amichne.kast.appserver.core

/** Optional operation-owned evidence for an already rejected physical input. */
internal sealed interface BrokerInputRejectionEvidence {
    data object Unspecified : BrokerInputRejectionEvidence

    data class Source(val cause: io.github.amichne.kast.protocol.contract.SourceReadCause) :
        BrokerInputRejectionEvidence
}
