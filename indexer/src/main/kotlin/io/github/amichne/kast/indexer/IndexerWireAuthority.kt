package io.github.amichne.kast.indexer

import io.github.amichne.kast.distribution.contract.WireRuntimeIdentity

sealed interface IndexerWireAuthority {
    data object Fixture : IndexerWireAuthority
    class Installed(val identity: WireRuntimeIdentity) : IndexerWireAuthority
}
