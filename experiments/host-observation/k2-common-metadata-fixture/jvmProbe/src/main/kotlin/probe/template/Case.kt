package probe.template
fun templateTarget(): String = "ok"
fun templateCaller(): String = "value=${templateTarget()}"
