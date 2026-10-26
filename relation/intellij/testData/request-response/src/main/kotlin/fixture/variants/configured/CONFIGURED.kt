package fixture.configured

class Payload

fun origin(): Payload = Payload()

fun consume(value: Payload) = Unit

fun forbidden(value: Payload) = Unit

data class Box(val first: Payload, val second: Payload) {
    val selected: Payload
        get() = second
}

fun getter(value: Box): Payload {
    (val output = selected) = value
    return output
}

fun square(value: Box): Payload {
    val [_, output] = value
    return output
}

fun withValue(value: Box, block: (Box) -> Payload): Payload = block(value)

class Repeating(val value: Box) {
    operator fun iterator(): Repeating = this

    operator fun hasNext(): Boolean = true

    operator fun next(): Box {
        return value
    }
}

fun namedGetterCase() {
    val box = Box(Payload(), origin())
    val output = getter(box)
    consume(/*<namedGetter-use>*/output/*</namedGetter-use>*/)
    forbidden(/*<namedGetter-forbidden>*/Payload()/*</namedGetter-forbidden>*/)
}

fun squareLocalCase() {
    val box = Box(Payload(), origin())
    val output = square(box)
    consume(/*<squareLocal-use>*/output/*</squareLocal-use>*/)
    forbidden(/*<squareLocal-forbidden>*/Payload()/*</squareLocal-forbidden>*/)
}

fun namedLambdaCase() {
    val box = Box(Payload(), origin())
    val output = withValue(box) { (second) -> second }
    consume(/*<namedLambda-use>*/output/*</namedLambda-use>*/)
    forbidden(/*<namedLambda-forbidden>*/Payload()/*</namedLambda-forbidden>*/)
}

fun namedLoopCase() {
    val box = Box(Payload(), origin())
    for ((second) in Repeating(box)) {
        consume(/*<namedLoop-use>*/second/*</namedLoop-use>*/)
        forbidden(/*<namedLoop-forbidden>*/Payload()/*</namedLoop-forbidden>*/)
    }
}
