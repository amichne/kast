package fixture.impact.equalitycontrol

class EqualityProbe(val id: Int) {
    var equalsCalls: Int = 0
        private set

    override fun equals(other: Any?): Boolean {
        equalsCalls += 1
        return other is EqualityProbe && id == other.id
    }

    override fun hashCode(): Int = id
}

fun source(id: Int): EqualityProbe = EqualityProbe(id)

fun nullComparison(id: Int): Boolean {
    val value: EqualityProbe? = /*<seed>*/ source(id) /*</seed>*/
    return /*<null-predicate>*/ value != null /*</null-predicate>*/
}

fun generalEquality(id: Int, right: EqualityProbe): Boolean {
    val left = /*<seed>*/ source(id) /*</seed>*/
    return /*<general-equality>*/ left == right /*</general-equality>*/
}

fun nullableOperand(id: Int, right: EqualityProbe?): Boolean {
    val left = /*<seed>*/ source(id) /*</seed>*/
    return /*<nullable-equality>*/ left == right /*</nullable-equality>*/
}
