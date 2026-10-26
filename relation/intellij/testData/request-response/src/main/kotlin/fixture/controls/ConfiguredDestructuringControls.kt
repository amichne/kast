package fixture.controls

data class ConfiguredBox(val first: String, val second: String)

class ConfiguredGetterBox(val first: String, val second: String) {
    val selected: String
        get() = second

    val stop: String
        get() = error("A discarded name-based property still executes")

    operator fun component1(): String = first

    operator fun component2(): String = error("A positional underscore skips this call")
}

fun configuredFull() {
    (val chosen = second) = ConfiguredBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredFullSwapped() {
    (val chosen = first) = ConfiguredBox("second-payload", "first-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredSquare() {
    val [chosen, _] = ConfiguredBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredParentheses() {
    val (second) = ConfiguredBox("first-payload", "second-payload")
    /*<use>*/second/*</use>*/.length
}

fun configuredSquareSkipsGetter() {
    val [chosen, _] = ConfiguredGetterBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredCustomGetter() {
    (val chosen = selected) = ConfiguredGetterBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredCustomGetterSwapped() {
    (val chosen = selected) = ConfiguredGetterBox("second-payload", "first-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredIgnoredNormalGetter() {
    (val chosen = first, val _ = selected) = ConfiguredGetterBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredEarlierThrowingGetter() {
    (val before = stop, val chosen = selected) = ConfiguredGetterBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredLaterThrowingGetter() {
    (val chosen = selected, val after = stop) = ConfiguredGetterBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}

fun configuredDiscardedGetter() {
    (val chosen = first, val _ = stop) = ConfiguredGetterBox("first-payload", "second-payload")
    /*<use>*/chosen/*</use>*/.length
}
