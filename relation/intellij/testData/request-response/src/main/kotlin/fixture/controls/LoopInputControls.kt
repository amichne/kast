package fixture.controls.loopinput

class LoopPayload

fun loopOrigin(): LoopPayload = LoopPayload()
fun otherLoopOrigin(): LoopPayload = LoopPayload()
fun loopConsume(value: LoopPayload) {}
fun loopForbidden(value: LoopPayload) {}

class LoopParts(val first: LoopPayload, val second: LoopPayload) {
    operator fun component1(): LoopPayload = second
    operator fun component2(): LoopPayload = first
}

class RepeatingLoop(val item: LoopParts) {
    operator fun iterator(): RepeatingLoop = this
    operator fun hasNext(): Boolean = true
    operator fun next(): LoopParts = item
}

class EmptyLoop(val item: LoopParts) {
    operator fun iterator(): EmptyLoop = this
    operator fun hasNext(): Boolean = false
    operator fun next(): LoopParts = error("A zero-iteration loop must not call next")
}

class ThrowingLoop(val item: LoopParts) {
    operator fun iterator(): ThrowingLoop = this
    operator fun hasNext(): Boolean = error("The guard does not complete normally")
    operator fun next(): LoopParts = item
}

class SelectingLoop(val first: LoopParts, val second: LoopParts) {
    operator fun iterator(): SelectingLoop = this
    operator fun hasNext(): Boolean = true
    operator fun next(): LoopParts = second
}

fun firstLoop() {
    val input = RepeatingLoop(LoopParts(LoopPayload(), loopOrigin()))
    /*<loop>*/for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }/*</loop>*/
}

fun secondLoop() {
    val input = RepeatingLoop(LoopParts(LoopPayload(), loopOrigin()))
    for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }
}

fun emptyLoop() {
    val input = EmptyLoop(LoopParts(LoopPayload(), loopOrigin()))
    /*<loop>*/for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }/*</loop>*/
}

fun throwingLoop() {
    val input = ThrowingLoop(LoopParts(LoopPayload(), loopOrigin()))
    /*<loop>*/for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }/*</loop>*/
}

fun selectedFieldLoop() {
    val input = SelectingLoop(
        LoopParts(LoopPayload(), loopOrigin()),
        LoopParts(LoopPayload(), otherLoopOrigin()),
    )
    for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }
}

class ThrowingLoopParts(val item: LoopPayload) {
    operator fun component1(): LoopPayload = item
    operator fun component2(): LoopPayload = error("The later component does not complete normally")
}

class LoopWithThrowingParts(val item: ThrowingLoopParts) {
    operator fun iterator(): LoopWithThrowingParts = this
    operator fun hasNext(): Boolean = true
    operator fun next(): ThrowingLoopParts = item
}

fun laterThrowingLoopComponent() {
    val input = LoopWithThrowingParts(ThrowingLoopParts(loopOrigin()))
    for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }
}

fun unusedThrowingLoopComponent() {
    val input = LoopWithThrowingParts(ThrowingLoopParts(loopOrigin()))
    for ((value, unused) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/LoopPayload()/*</forbidden>*/)
    }
}

fun underscoreThrowingLoopComponent() {
    val input = LoopWithThrowingParts(ThrowingLoopParts(loopOrigin()))
    for ((value, _) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/LoopPayload()/*</forbidden>*/)
    }
}

fun explicitAndImplicitNext() {
    val input = RepeatingLoop(LoopParts(LoopPayload(), loopOrigin()))
    val (direct, unrelatedDirect) = /*<explicit>*/input.next()/*</explicit>*/
    loopConsume(/*<explicit-use>*/direct/*</explicit-use>*/)
    loopForbidden(unrelatedDirect)
    for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }
}

class SelectingIterable(val first: RepeatingLoop, val second: RepeatingLoop) {
    operator fun iterator(): RepeatingLoop = second
}

fun selectedIteratorLoop() {
    val input = SelectingIterable(
        RepeatingLoop(LoopParts(LoopPayload(), loopOrigin())),
        RepeatingLoop(LoopParts(LoopPayload(), otherLoopOrigin())),
    )
    for ((value, unrelated) in /*<range>*/input/*</range>*/) {
        loopConsume(/*<use>*/value/*</use>*/)
        loopForbidden(/*<forbidden>*/unrelated/*</forbidden>*/)
    }
}
