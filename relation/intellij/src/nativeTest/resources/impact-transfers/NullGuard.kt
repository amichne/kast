package fixture.impact.nullguard

data class Tracked(val id: String)

fun source(input: Tracked?): Tracked? = input

fun guarded(input: Tracked?): String {
    val value = /*<seed>*/ source(input) /*</seed>*/
    return if (/*<predicate>*/ value != null /*</predicate>*/) {
        /*<read>*/ value.id /*</read>*/
    } else {
        /*<excluded>*/ "absent" /*</excluded>*/
    }
}

fun reversed(input: Tracked?): String {
    val value = /*<seed>*/ source(input) /*</seed>*/
    return if (/*<predicate>*/ null != value /*</predicate>*/) {
        /*<read>*/ value.id /*</read>*/
    } else {
        /*<excluded>*/ "absent" /*</excluded>*/
    }
}

fun inverse(input: Tracked?): String {
    val value = /*<seed>*/ source(input) /*</seed>*/
    return if (/*<predicate>*/ value == null /*</predicate>*/) {
        /*<excluded>*/ "absent" /*</excluded>*/
    } else {
        /*<read>*/ value.id /*</read>*/
    }
}
