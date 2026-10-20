package io.github.amichne.kast.runtime.hosted

internal suspend fun NativePacketCompositeQueryCase.assertErrorReturnsThroughC25C26() {
    val facts = errorReturnFacts()
    NativePacketCompositeErrorReturnOracle(facts).assertRequiredRows(captureOriginalS2ErrorReturns(facts))
}

internal suspend fun NativePacketCompositeQueryCase.assertErrorReturnForbiddenWitnesses() {
    val facts = errorReturnFacts()
    NativePacketCompositeErrorReturnOracle(facts).assertForbiddenWitnesses(captureOriginalS2ErrorReturns(facts))
}

private suspend fun NativePacketCompositeQueryCase.captureOriginalS2ErrorReturns(
    facts: NativePacketCompositeErrorReturnFacts
) =
    NativePacketQueryCapture(services)
        .paths(
            producer = source.producerS2(),
            targets = listOf(facts.common.getOfferReturn),
            outgoing = false,
            declarations = source.requestedDeclarations(),
        )
