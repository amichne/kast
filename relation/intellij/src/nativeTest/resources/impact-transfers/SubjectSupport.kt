package fixture.impact.subject

class Leaf(val id: String)

data class Payload(val left: Leaf, val right: Leaf)

data class Readout(val selected: String, val other: String)

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    data object Error : Outcome<Nothing>
}

fun selectedProducer(id: String): Leaf = Leaf(id)

fun otherProducer(id: String): Leaf = Leaf(id)

fun produce(selectedId: String, otherId: String, succeed: Boolean): Outcome<Payload> {
    val selected = /*<seed>*/ selectedProducer(selectedId) /*</seed>*/
    val other = otherProducer(otherId)
    return if (succeed) {
        Outcome.Success(Payload(left = selected, right = other))
    } else {
        Outcome.Error
    }
}
