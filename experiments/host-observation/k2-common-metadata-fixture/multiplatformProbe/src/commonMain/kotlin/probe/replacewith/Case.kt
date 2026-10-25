package probe.replacewith
fun replacementTarget(): String = "ok"
fun replacementCaller(): String = replacementTarget()
@Deprecated("replacementTarget", ReplaceWith("replacementTarget()"))
fun unrelatedReplacement(): String = "unused"
