package probe.deprecated
fun annotationTarget(): String = "ok"
fun annotationCaller(): String = annotationTarget()
@Deprecated("annotationTarget")
fun unrelatedDeprecated(): String = "unused"
