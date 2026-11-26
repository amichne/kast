package fixture.impact.equalitysink

class Token(val id: Int)

open class OpenToken(val id: Int)

interface Keyed {
    val id: Int
}

class KeyedToken(override val id: Int) : Keyed

class Probe(val id: Int) {
    override fun equals(other: Any?): Boolean = other is Probe && other.id == id

    override fun hashCode(): Int = id
}

fun source(id: Int): Token = Token(id)

fun text(id: Int): String = "t$id"

fun open(id: Int): OpenToken = OpenToken(id)

fun keyed(id: Int): Keyed = KeyedToken(id)

fun finalLeft(id: Int, other: Token): Boolean {
    val value = /*<seed>*/ source(id) /*</seed>*/
    return /*<compare>*/ value == other /*</compare>*/
}

fun finalRight(id: Int, other: Token): Boolean {
    val value = /*<seed>*/ source(id) /*</seed>*/
    return /*<compare>*/ other != value /*</compare>*/
}

fun identity(id: Int, other: Any): Boolean {
    val value = /*<seed>*/ source(id) /*</seed>*/
    return /*<compare>*/ value === other /*</compare>*/
}

fun stringEquality(id: Int, other: String): Boolean {
    val value = /*<seed>*/ text(id) /*</seed>*/
    return /*<compare>*/ value == other /*</compare>*/
}

fun openEquality(id: Int, other: OpenToken): Boolean {
    val value = /*<seed>*/ open(id) /*</seed>*/
    return /*<compare>*/ value == other /*</compare>*/
}

fun interfaceEquality(id: Int, other: Keyed): Boolean {
    val value = /*<seed>*/ keyed(id) /*</seed>*/
    return /*<compare>*/ value == other /*</compare>*/
}

fun overriddenReceivesValue(id: Int, probe: Probe): Boolean {
    val value = /*<seed>*/ source(id) /*</seed>*/
    return /*<compare>*/ probe == value /*</compare>*/
}
