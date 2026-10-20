package fixture.controls

import fixture.outcome.Outcome
import fixture.wire.Envelope

class SourceFunctionOriginControls<E, S>(
    private val primarySupplier: () -> Outcome<E, Envelope<S>>,
    private val siblingSupplier: () -> Outcome<E, Envelope<S>>,
    private var mutableSupplier: () -> Outcome<E, Envelope<S>>,
) {
    fun twoCalls(): Outcome<E, Envelope<S>> {
        primarySupplier()
        return primarySupplier()
    }

    fun siblingCall(): Outcome<E, Envelope<S>> = siblingSupplier()

    fun replaceAndCall(replacement: () -> Outcome<E, Envelope<S>>): Outcome<E, Envelope<S>> {
        mutableSupplier = replacement
        return mutableSupplier()
    }
}
