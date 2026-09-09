package io.github.amichne.kast.distribution.contract

/** Installed wire limits shared by launcher inspection and indexer ingress. No environment overrides. */
object IndexerTransportLimits {
    const val maximumFrameBytes: Int = 8 * 1_024 * 1_024
    const val defaultConnections: Int = 8
    const val maximumConnections: Int = 64
}
