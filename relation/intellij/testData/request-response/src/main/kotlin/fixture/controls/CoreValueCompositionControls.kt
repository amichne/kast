package fixture.controls.corevalue

data class Parcel(val first: String, val second: String)

sealed interface Stage {
    fun deliver(): Parcel
}

class PayloadStage(val selected: String, val other: String) : Stage {
    override fun deliver(): Parcel = Parcel(selected, other)
}

class ConstantStage(val ignored: String) : Stage {
    override fun deliver(): Parcel = Parcel("constant", "other")
}

fun trackedSource(): String = "tracked"

fun otherSource(): String = "unrelated"

fun keepSecond(value: String): String = value

fun payloadConsumer(): String {
    val tracked = trackedSource()
    val other = otherSource()
    val stage: Stage = PayloadStage(tracked, other)
    val (result, second) = /*<payload-receiver>*/stage/*</payload-receiver>*/.deliver()
    keepSecond(/*<payload-second>*/second/*</payload-second>*/)
    return /*<payload-result>*/result/*</payload-result>*/
}

fun constantConsumer(): String {
    val tracked = trackedSource()
    val stage: Stage = ConstantStage(tracked)
    val (result, second) = /*<constant-receiver>*/stage/*</constant-receiver>*/.deliver()
    keepSecond(/*<constant-second>*/second/*</constant-second>*/)
    return /*<constant-result>*/result/*</constant-result>*/
}
