package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.TraversalRunResult
internal object CanonicalSymbolSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val discoverRequest = factory.create(
        SymbolDiscoverRequestWireDocument.serializer(),
        SymbolDiscoverRequest::toSymbolWireDocument,
        SymbolDiscoverRequestWireDocument::toContract,
    )
    val discoverResult = factory.create(
        SymbolDiscoverResultWireDocument.serializer(),
        SymbolDiscoverResult::toSymbolWireDocument,
        SymbolDiscoverResultWireDocument::toContract,
    )
    val describeResult = factory.create(
        SymbolDescribeResultWireDocument.serializer(),
        SymbolDescribeResult::toSymbolWireDocument,
        SymbolDescribeResultWireDocument::toContract,
    )
    val relationResult = factory.create(
        RelationReadResultWireDocument.serializer(),
        RelationReadResult::toSymbolWireDocument,
        RelationReadResultWireDocument::toContract,
    )
    val traversalResult = factory.create(
        TraversalRunResultWireDocument.serializer(),
        TraversalRunResult::toSymbolWireDocument,
        TraversalRunResultWireDocument::toContract,
    )
}
