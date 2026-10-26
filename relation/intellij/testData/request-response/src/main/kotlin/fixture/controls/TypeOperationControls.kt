package fixture.controls

interface CastView

class CastValue(val payload: String) : CastView

class OtherCastValue

fun upcast(value: CastValue) {
    val result = /*<input>*/value/*</input>*/ as CastView
    /*<use>*/result/*</use>*/
}

fun safeUpcast(value: CastValue) {
    val result = /*<input>*/value/*</input>*/ as? CastView
    /*<use>*/result/*</use>*/
}

fun failedCast(value: CastValue) {
    val result = /*<input>*/value/*</input>*/ as OtherCastValue
    /*<use>*/result/*</use>*/
}

fun failedSafeCast(value: CastValue) {
    val result = /*<input>*/value/*</input>*/ as? OtherCastValue
    /*<use>*/result/*</use>*/
}

fun unknownCast(value: Any) {
    val result = /*<input>*/value/*</input>*/ as CastValue
    /*<use>*/result/*</use>*/
}

fun typeTest(value: Any) {
    val result = /*<input>*/value/*</input>*/ is CastValue
    /*<use>*/result/*</use>*/
}

fun negatedTypeTest(value: Any) {
    val result = /*<input>*/value/*</input>*/ !is CastValue
    /*<use>*/result/*</use>*/
}

fun wholeFailedCast(value: CastValue) {
    val result = /*<cast>*/value as OtherCastValue/*</cast>*/
    /*<use>*/result/*</use>*/
}

fun wholeFailedSafeCast(value: CastValue) {
    val result = /*<cast>*/value as? OtherCastValue/*</cast>*/
    /*<use>*/result/*</use>*/
}

fun parenthesizedWholeFailedCast(value: CastValue) {
    val result = /*<cast>*/(value as OtherCastValue)/*</cast>*/
    /*<use>*/result/*</use>*/
}

fun nestedWholeFailedCast(value: CastValue) {
    val result = /*<cast>*/(value as OtherCastValue) as Any/*</cast>*/
    /*<use>*/result/*</use>*/
}

fun nothingOperand() {
    val result = /*<cast>*/error("stop") as Any/*</cast>*/
    /*<use>*/result/*</use>*/
}

fun nullableNothingOperand() {
    val result = /*<cast>*/null as String?/*</cast>*/
    /*<use>*/result/*</use>*/
}

class GenericOuter<T> {
    inner class Member
}

fun unsafeGenericInnerCast(value: GenericOuter<String>.Member) {
    val result = /*<input>*/value/*</input>*/ as GenericOuter<Int>.Member
    /*<use>*/result/*</use>*/
}

fun safeGenericInnerCast(value: GenericOuter<String>.Member) {
    val result = /*<input>*/value/*</input>*/ as? GenericOuter<Int>.Member
    /*<use>*/result/*</use>*/
}
