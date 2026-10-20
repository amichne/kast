package io.github.amichne.kast.runtime.hosted

/** The selectors use the existing fixture, capture, and production query coordinator. */
internal suspend fun NativePacketCompositeQueryCase.assertClientErrorC24Required() {
    val facts = clientErrorFacts()
    NativePacketCompositeQueryErrorOracle(facts).assertRequiredRow(captureClientErrorC24(facts))
}

internal suspend fun NativePacketCompositeQueryCase.assertClientErrorC24Forbidden() {
    val facts = clientErrorFacts()
    NativePacketCompositeQueryErrorOracle(facts).assertForbiddenWitnesses(captureClientErrorC24(facts))
}

private suspend fun NativePacketCompositeQueryCase.captureClientErrorC24(facts: NativePacketCompositeQueryErrorFacts) =
    NativePacketQueryCapture(services)
        .paths(
            producer = source.producerS2(),
            targets = listOf(facts.mapperReturn),
            outgoing = false,
            declarations = source.requestedDeclarations() + facts.mapperDeclaration,
        )
