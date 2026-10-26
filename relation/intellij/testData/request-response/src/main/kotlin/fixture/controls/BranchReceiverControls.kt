package fixture.controls.branchreceiver

sealed interface Choice
class Selected(val payload: String) : Choice
class Other(val payload: String) : Choice

fun Choice.implicit(): String = when (this) {
    is Selected -> /*<selected>*/payload/*</selected>*/
    is Other -> /*<other>*/payload/*</other>*/
}

fun Choice.named(): String = when (val subject = this) {
    is Selected -> /*<selected>*/subject.payload/*</selected>*/
    is Other -> /*<other>*/subject.payload/*</other>*/
}

fun Choice.aliased(): String {
    val subject = this
    return when (subject) {
        is Selected -> /*<selected>*/subject.payload/*</selected>*/
        is Other -> /*<other>*/subject.payload/*</other>*/
    }
}

fun Choice.whole(): Choice = when (this) {
    is Selected -> /*<selected>*/this/*</selected>*/
    is Other -> /*<other>*/this/*</other>*/
}

interface Marker
open class Open(val payload: String)
class Both(payload: String) : Open(payload), Marker
fun Open.overlap(): String = when (this) {
    is Marker -> /*<overlap>*/payload/*</overlap>*/
    else -> "constant"
}

class Holder(val payload: String) {
    fun shadowCaller(): String = Selected("tracked").shadow()
    fun Choice.shadow(): String = when (this) {
        is Selected -> /*<shadow>*/this@Holder.payload/*</shadow>*/
        is Other -> "constant"
    }
}

fun implicitCaller(): String = Selected("tracked").implicit()
fun namedCaller(): String = Selected("tracked").named()
fun aliasedCaller(): String = Selected("tracked").aliased()
fun wholeCaller(): Choice = Selected("tracked").whole()
fun overlapCaller(): String = Open("tracked").overlap()

fun Choice.deepAliases(): String {
    val a = this
    val b = a
    val c = b
    val d = c
    val e = d
    val f = e
    val g = f
    val h = g
    val i = h
    return when (i) {
        is Selected -> i.payload
        is Other -> i.payload
    }
}
fun deepAliasCaller(): String = Selected("tracked").deepAliases()

fun Choice.echo(): Choice = this
fun Choice.called(): Choice = when (this) {
    is Selected -> /*<selected>*/echo()/*</selected>*/
    is Other -> /*<other>*/echo()/*</other>*/
}
fun calledCaller(): Choice = Selected("tracked").called()

sealed interface GenericChoice<out T>
class GenericSelected<T>(val payload: T) : GenericChoice<T>
class GenericOther<T>(val payload: T) : GenericChoice<T>
fun GenericChoice<*>.generic(): Any? = when (this) {
    is GenericSelected<*> -> /*<selected>*/payload/*</selected>*/
    is GenericOther<*> -> /*<other>*/payload/*</other>*/
}
fun genericCaller(): Any? = GenericSelected("tracked").generic()

fun GenericChoice<String>.concrete(): String = when (this) {
    is GenericSelected<String> -> payload
    is GenericOther<String> -> payload
}
fun concreteCaller(): String = GenericSelected("tracked").concrete()

fun consume(value: String) {}
fun Choice.localPayload() {
    when (this) {
        is Selected -> {
            val result = payload
            consume(/*<selected>*/result/*</selected>*/)
        }
        is Other -> {
            val result = payload
            consume(/*<other>*/result/*</other>*/)
        }
    }
}
fun localPayloadCaller() = Selected("tracked").localPayload()

fun unknownSubject(value: Choice) {
    when (value) {
        is Selected -> consume(/*<selected>*/value/*</selected>*/.payload)
        is Other -> consume(/*<other>*/value/*</other>*/.payload)
    }
}
fun unknownCaller(value: Choice) = unknownSubject(/*<source>*/value/*</source>*/)

fun Choice.negated(): Choice = when (this) {
    !is Selected -> /*<other>*/this/*</other>*/
    else -> /*<selected>*/this/*</selected>*/
}
fun negatedCaller(): Choice = Selected("tracked").negated()

class BranchOuter<T> {
    inner class Inner(val payload: String)
}

fun BranchOuter<String>.Inner.innerStar(): String = when (this) {
    is BranchOuter<*>.Inner -> /*<selected>*/payload/*</selected>*/
    else -> /*<other>*/payload/*</other>*/
}

fun BranchOuter<String>.Inner.innerConcrete(): String = when (this) {
    is BranchOuter<String>.Inner -> payload
    else -> payload
}

fun innerStarCaller(): String = BranchOuter<String>().Inner("tracked").innerStar()
fun innerConcreteCaller(): String = BranchOuter<String>().Inner("tracked").innerConcrete()
