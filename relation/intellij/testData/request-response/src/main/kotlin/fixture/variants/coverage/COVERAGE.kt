@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package fixture.coverage

import kotlin.contracts.contract

class Payload
fun origin(): Payload = Payload()
fun consume(value: Payload) {}
fun forbidden(value: Payload) {}

sealed interface Choice {
    operator fun component1(): Payload
}
class Selected(val payload: Payload) : Choice {
    override operator fun component1(): Payload = payload
}
class Other(val payload: Payload) : Choice {
    override operator fun component1(): Payload = Payload()
}

fun Choice.mapError(): Payload = when (this) {
    is Selected -> payload
    is Other -> payload
}

fun implicitCase() {
    val input = Selected(origin())
    val output = input.mapError()
    consume(/*<implicit-use>*/output/*</implicit-use>*/)
    forbidden(/*<implicit-forbidden>*/Payload()/*</implicit-forbidden>*/)
}

fun componentCase() {
    val input = Selected(origin())
    val (output) = input
    consume(/*<component-use>*/output/*</component-use>*/)
    forbidden(/*<component-forbidden>*/Payload()/*</component-forbidden>*/)
}

fun Choice.named(): Payload = when (val self = this) {
    is Selected -> self.payload
    is Other -> self.payload
}

fun namedCase() {
    val input = Selected(origin())
    val output = input.named()
    consume(/*<named-use>*/output/*</named-use>*/)
    forbidden(/*<named-forbidden>*/Payload()/*</named-forbidden>*/)
}

fun Choice.casted(): Payload = if (this is Selected) (this as Selected).payload else Payload()

fun castCase() {
    val input = Selected(origin())
    val output = input.casted()
    consume(/*<cast-use>*/output/*</cast-use>*/)
    forbidden(/*<cast-forbidden>*/Payload()/*</cast-forbidden>*/)
}

fun Choice.isSelected(): Boolean {
    contract { returns(true) implies (this@isSelected is Selected) }
    return this is Selected
}

fun Choice.predicated(): Payload = if (isSelected()) payload else Payload()

fun predicateCase() {
    val input = Selected(origin())
    val output = input.predicated()
    consume(/*<predicate-use>*/output/*</predicate-use>*/)
    forbidden(/*<predicate-forbidden>*/Payload()/*</predicate-forbidden>*/)
}

interface Scope {
    fun Choice.bind(): Payload = when (this) {
        is Selected -> payload
        is Other -> payload
    }

    fun Payload.pack(): SwappedParts = SwappedParts(Payload(), this)

    fun Payload.constantPack(): SwappedParts = SwappedParts(Payload(), Payload())
}

class BindingScope : Scope {
    fun bindCase() {
        val self = Selected(origin())
        val output = self.bind()
        consume(/*<bind-use>*/output/*</bind-use>*/)
        forbidden(/*<bind-forbidden>*/Payload()/*</bind-forbidden>*/)
    }

    fun compositionCase() {
        val input = Selected(origin())
        val refined = input.predicated()
        val parts = refined.pack()
        val output = invokeParts(parts) /*<composition-callback>*/{ /*<composition-parameter>*/(/*<composition-binding>*/value/*</composition-binding>*/, unrelated)/*</composition-parameter>*/ ->
            forbidden(/*<composition-forbidden>*/unrelated/*</composition-forbidden>*/)
            /*<composition-callback-use>*/value/*</composition-callback-use>*/
        }/*</composition-callback>*/
        consume(/*<composition-use>*/output/*</composition-use>*/)
    }

    fun constantCompositionCase() {
        val input = Selected(origin())
        val refined = input.predicated()
        val parts = refined.constantPack()
        val output = invokeParts(parts) { (value, unrelated) ->
            forbidden(/*<constantComposition-forbidden>*/unrelated/*</constantComposition-forbidden>*/)
            value
        }
        consume(/*<constantComposition-use>*/output/*</constantComposition-use>*/)
    }

    fun incompatibleCompositionCase() {
        val input = Other(origin())
        val refined = input.predicated()
        val parts = refined.pack()
        val output = invokeParts(parts) { (value, unrelated) ->
            forbidden(/*<incompatibleComposition-forbidden>*/unrelated/*</incompatibleComposition-forbidden>*/)
            value
        }
        consume(/*<incompatibleComposition-use>*/output/*</incompatibleComposition-use>*/)
    }
}

fun predicateDeclarationCase() {
    val input = Selected(origin())
    input.isSelected()
}

fun force(value: Any): Payload = value as Payload

fun unknownCastCase() {
    val output = force(origin())
    consume(/*<unknownCast-use>*/output/*</unknownCast-use>*/)
    forbidden(/*<unknownCast-forbidden>*/Payload()/*</unknownCast-forbidden>*/)
}

fun Choice.falsePredicate(): Boolean {
    contract { returns(false) implies (this@falsePredicate is Selected) }
    return this !is Selected
}

fun Choice.predicatedFalse(): Payload = if (!falsePredicate()) payload else Payload()

fun predicateFalseCase() {
    val input = Selected(origin())
    val output = input.predicatedFalse()
    consume(/*<predicateFalse-use>*/output/*</predicateFalse-use>*/)
    forbidden(/*<predicateFalse-forbidden>*/Payload()/*</predicateFalse-forbidden>*/)
}

fun Choice.nullPredicate(): Boolean? {
    contract { returns(null) implies (this@nullPredicate is Selected) }
    return if (this is Selected) null else false
}

fun Choice.predicatedNull(): Payload = if (nullPredicate() == null) payload else Payload()

fun predicateNullCase() {
    val input = Selected(origin())
    val output = input.predicatedNull()
    consume(/*<predicateNull-use>*/output/*</predicateNull-use>*/)
    forbidden(/*<predicateNull-forbidden>*/Payload()/*</predicateNull-forbidden>*/)
}

fun Choice.nonNullPredicate(): Boolean? {
    contract { returnsNotNull() implies (this@nonNullPredicate is Selected) }
    return if (this is Selected) true else null
}

fun Choice.predicatedNonNull(): Payload = if (nonNullPredicate() != null) payload else Payload()

fun predicateNonNullCase() {
    val input = Selected(origin())
    val output = input.predicatedNonNull()
    consume(/*<predicateNonNull-use>*/output/*</predicateNonNull-use>*/)
    forbidden(/*<predicateNonNull-forbidden>*/Payload()/*</predicateNonNull-forbidden>*/)
}

fun Choice.assertSelected() {
    contract { returns() implies (this@assertSelected is Selected) }
    if (this !is Selected) error("not selected")
}

fun Choice.predicatedNormal(): Payload {
    assertSelected()
    return payload
}

fun predicateNormalCase() {
    val input = Selected(origin())
    val output = input.predicatedNormal()
    consume(/*<predicateNormal-use>*/output/*</predicateNormal-use>*/)
    forbidden(/*<predicateNormal-forbidden>*/Payload()/*</predicateNormal-forbidden>*/)
}

fun Choice.guarded(): Payload {
    if (this !is Selected) return Payload()
    return payload
}

fun guardCase() {
    val input = Selected(origin())
    val output = input.guarded()
    consume(/*<guard-use>*/output/*</guard-use>*/)
    forbidden(/*<guard-forbidden>*/Payload()/*</guard-forbidden>*/)
}

fun Choice.shortCircuited(enabled: Boolean): Payload = if (this is Selected && enabled) payload else Payload()

fun shortCircuitCase() {
    val input = Selected(origin())
    val output = input.shortCircuited(true)
    consume(/*<shortCircuit-use>*/output/*</shortCircuit-use>*/)
    forbidden(/*<shortCircuit-forbidden>*/Payload()/*</shortCircuit-forbidden>*/)
}

fun Choice.whenGuarded(enabled: Boolean): Payload = when (this) {
    is Selected if enabled -> payload
    else -> Payload()
}

fun whenGuardCase() {
    val input = Selected(origin())
    val output = input.whenGuarded(true)
    consume(/*<whenGuard-use>*/output/*</whenGuard-use>*/)
    forbidden(/*<whenGuard-forbidden>*/Payload()/*</whenGuard-forbidden>*/)
}

class SwappedParts(val first: Payload, val second: Payload) {
    operator fun component1(): Payload = second
    operator fun component2(): Payload = first
}

fun orderedComponentsCase() {
    val input = SwappedParts(Payload(), origin())
    val (output, unrelated) = input
    consume(/*<orderedComponents-use>*/output/*</orderedComponents-use>*/)
    forbidden(/*<orderedComponents-forbidden>*/unrelated/*</orderedComponents-forbidden>*/)
}

fun incompatiblePredicateCase() {
    val input = Other(origin())
    val output = input.predicated()
    forbidden(/*<incompatiblePredicate-forbidden>*/output/*</incompatiblePredicate-forbidden>*/)
}

fun selectedArgument(first: Choice, second: Choice): Boolean {
    contract { returns(true) implies (second is Selected) }
    return second is Selected
}

fun Choice.wrongArgument(other: Choice): Payload =
    if (selectedArgument(this, other)) other.payload else Payload()

fun wrongArgumentCase() {
    val input = Selected(origin())
    val output = input.wrongArgument(Selected(Payload()))
    forbidden(/*<wrongArgument-forbidden>*/output/*</wrongArgument-forbidden>*/)
}

fun invokeParts(value: SwappedParts, action: (SwappedParts) -> Payload): Payload = action(value)

fun lambdaInputCase() {
    val output = invokeParts(SwappedParts(Payload(), origin())) { (value, unrelated) ->
        forbidden(/*<lambdaInput-forbidden>*/unrelated/*</lambdaInput-forbidden>*/)
        value
    }
    consume(/*<lambdaInput-use>*/output/*</lambdaInput-use>*/)
}

class RepeatingParts(val item: SwappedParts) {
    operator fun iterator(): RepeatingParts = this
    operator fun hasNext(): Boolean = true
    operator fun next(): SwappedParts = item
}

fun loopInputCase() {
    val input = RepeatingParts(SwappedParts(Payload(), origin()))
    for ((value, unrelated) in input) {
        consume(/*<loopInput-use>*/value/*</loopInput-use>*/)
        forbidden(/*<loopInput-forbidden>*/unrelated/*</loopInput-forbidden>*/)
    }
}

@OptIn(kotlin.contracts.ExperimentalExtendedContracts::class)
fun chooseHeld(condition: Boolean, selected: () -> Payload): Payload {
    contract {
        callsInPlace(selected, kotlin.contracts.InvocationKind.AT_MOST_ONCE)
        condition holdsIn selected
    }
    return if (condition) selected() else Payload()
}

fun held(input: Choice): Payload = chooseHeld(input is Selected) { input.payload }

fun holdsInCase() {
    val input = Selected(origin())
    val output = held(input)
    consume(/*<holdsIn-use>*/output/*</holdsIn-use>*/)
    forbidden(/*<holdsIn-forbidden>*/Payload()/*</holdsIn-forbidden>*/)
}
