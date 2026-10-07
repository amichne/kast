package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CallbackFactoryBodyCanonicalTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `changing only a returned body named target changes canonical proof`(): Unit =
        with(fixture) {
            val at = occurrence(280, 290)
            val baseline = returned(CallbackFactoryBodyCall.Named(at, endpoint("betaTarget", 610, 700, emptyList())))
            val changed = returned(CallbackFactoryBodyCall.Named(at, endpoint("gammaTarget", 710, 790, emptyList())))
            assertEquals(baseline.source, changed.source)
            assertEquals(baseline.destination, changed.destination)
            assertEquals(baseline.transfers, changed.transfers)
            assertNotEquals(baseline.canonicalProjection(), changed.canonicalProjection())
        }

    @Test
    fun `changing only a returned body call occurrence changes canonical proof`(): Unit =
        with(fixture) {
            val target = endpoint("betaTarget", 610, 700, emptyList())
            val baseline = returned(CallbackFactoryBodyCall.Named(occurrence(280, 290), target))
            val changed = returned(CallbackFactoryBodyCall.Named(occurrence(282, 292), target))
            assertEquals(baseline.source, changed.source)
            assertEquals(baseline.destination, changed.destination)
            assertEquals(baseline.transfers, changed.transfers)
            assertNotEquals(baseline.canonicalProjection(), changed.canonicalProjection())
        }

    private fun returned(entry: CallbackFactoryBodyCall.Named): ImmutableCallbackValue =
        with(fixture) {
            val call =
                ValueInvocation.fromCompiler(caller, range(20, 80), endpoint("factory", 210, 400, emptyList())).value()
            val body = anonymous(270, 300)
            val local = ValueSite.fromCompiler(call.callable, body.range, ValueRole.ExpressionResult).value()
            val returned =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        local,
                        local,
                        emptyList(),
                    )
                    .value()
            val factory =
                CallbackFactoryReturn.fromCompiler(call, returned, emptyList(), bodyCalls(body, listOf(entry))).value()
            ImmutableCallbackValue.fromCompiler(
                    ImmutableCallbackValueOrigin.Returned(factory),
                    call.resultSite(),
                    call.resultSite(),
                    emptyList(),
                )
                .value()
        }
}
