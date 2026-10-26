package fixture.controls

data class LocalVersionPayload(val text: String)

fun originalLocalPayload(): LocalVersionPayload = LocalVersionPayload("original")

fun replacementLocalPayload(): LocalVersionPayload = LocalVersionPayload("replacement")

fun consumeLocalPayload(value: LocalVersionPayload): Unit = Unit

fun reassignedLocalPayload() {
    /*<initial-binding>*/var value = originalLocalPayload()/*</initial-binding>*/
    consumeLocalPayload(/*<before>*/value/*</before>*/)
    /*<replacement-binding>*/value = replacementLocalPayload()/*</replacement-binding>*/
    consumeLocalPayload(/*<after>*/value/*</after>*/)
}

fun selfAssignedLocalPayload() {
    /*<initial-binding>*/var value = originalLocalPayload()/*</initial-binding>*/
    consumeLocalPayload(/*<before>*/value/*</before>*/)
    /*<write>*/value/*</write>*/ = /*<rhs>*/value/*</rhs>*/
    consumeLocalPayload(/*<after>*/value/*</after>*/)
}

fun conditionallyAssignedLocalPayload(replace: Boolean) {
    /*<initial-binding>*/var value = originalLocalPayload()/*</initial-binding>*/
    if (replace) {
        /*<write>*/value/*</write>*/ = replacementLocalPayload()
    }
    consumeLocalPayload(/*<after>*/value/*</after>*/)
}

fun capturedLocalPayload() {
    /*<initial-binding>*/var value = originalLocalPayload()/*</initial-binding>*/
    val replace = {
        /*<write>*/value/*</write>*/ = replacementLocalPayload()
    }
    replace()
    consumeLocalPayload(/*<after>*/value/*</after>*/)
}

operator fun LocalVersionPayload.plus(other: LocalVersionPayload): LocalVersionPayload = other

operator fun LocalVersionPayload.inc(): LocalVersionPayload = replacementLocalPayload()

fun compoundAssignedLocalPayload() {
    var value = originalLocalPayload()
    /*<write>*/value/*</write>*/ += replacementLocalPayload()
    consumeLocalPayload(/*<after>*/value/*</after>*/)
}

fun incrementedLocalPayload() {
    var value = originalLocalPayload()
    (/*<write>*/value/*</write>*/)++
    consumeLocalPayload(/*<after>*/value/*</after>*/)
}

fun stableIfLocalPayload() {
    var value: Any = originalLocalPayload()
    if (/*<guard>*/value/*</guard>*/ is LocalVersionPayload) {
        consumeLocalPayload(/*<selected>*/value/*</selected>*/)
    }
    val unrelated = replacementLocalPayload()
    consumeLocalPayload(/*<unrelated>*/unrelated/*</unrelated>*/)
}

fun stableWhenLocalPayload() {
    var value: Any = originalLocalPayload()
    when (/*<guard>*/value/*</guard>*/) {
        is LocalVersionPayload -> consumeLocalPayload(/*<selected>*/value/*</selected>*/)
        else -> Unit
    }
    val unrelated = replacementLocalPayload()
    consumeLocalPayload(/*<unrelated>*/unrelated/*</unrelated>*/)
}

fun stableShortCircuitLocalPayload(enabled: Boolean) {
    var value: Any = originalLocalPayload()
    if (/*<guard>*/value/*</guard>*/ is LocalVersionPayload && enabled) {
        consumeLocalPayload(/*<selected>*/value/*</selected>*/)
    }
    val unrelated = replacementLocalPayload()
    consumeLocalPayload(/*<unrelated>*/unrelated/*</unrelated>*/)
}

fun stableGuardLocalPayload() {
    var value: Any = originalLocalPayload()
    var unrelatedCounter = 0
    unrelatedCounter++
    if (/*<guard>*/value/*</guard>*/ !is LocalVersionPayload) return
    consumeLocalPayload(/*<selected>*/value/*</selected>*/)
    val unrelated = replacementLocalPayload()
    consumeLocalPayload(/*<unrelated>*/unrelated/*</unrelated>*/)
}

fun stableNestedLocalPayload(enabled: Boolean) {
    if (enabled) {
        var value: Any = originalLocalPayload()
        if (/*<guard>*/value/*</guard>*/ is LocalVersionPayload) {
            consumeLocalPayload(/*<selected>*/value/*</selected>*/)
        }
        val unrelated = replacementLocalPayload()
        consumeLocalPayload(/*<unrelated>*/unrelated/*</unrelated>*/)
    }
}

fun unknownLocalPayload(enabled: Boolean): Any = if (enabled) originalLocalPayload() else "other"

fun stableUnknownLocalPayload(enabled: Boolean) {
    var value = unknownLocalPayload(enabled)
    if (/*<guard>*/value/*</guard>*/ is LocalVersionPayload) {
        consumeLocalPayload(/*<selected>*/value/*</selected>*/)
    }
}

fun reassignedRefinedLocalPayload() {
    var value: Any = originalLocalPayload()
    if (/*<before-guard>*/value/*</before-guard>*/ is LocalVersionPayload) {
        consumeLocalPayload(/*<before-selected>*/value/*</before-selected>*/)
    }
    /*<replacement-binding>*/value = replacementLocalPayload()/*</replacement-binding>*/
    when (/*<after-guard>*/value/*</after-guard>*/) {
        is LocalVersionPayload -> consumeLocalPayload(/*<after-selected>*/value/*</after-selected>*/)
        else -> Unit
    }
}
