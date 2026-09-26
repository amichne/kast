package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolInspectResult

internal object CanonicalSymbolSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val discoverRequest = factory.create(SymbolDiscoverRequest.serializer())
    val discoverResult =
        factory.create(
            SymbolDiscoverResultWireDocument.serializer(),
            SymbolDiscoverResult::toSymbolWireDocument,
            SymbolDiscoverResultWireDocument::toContract,
        )
    val describeResult =
        factory.create(
            SymbolInspectResultWireDocument.serializer(),
            SymbolInspectResult::toSymbolWireDocument,
            SymbolInspectResultWireDocument::toContract,
        )
}
