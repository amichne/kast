package probe.literal
fun literalTarget(): String = "ok"
fun literalCaller(): String = literalTarget()
fun unrelatedLiteral(): String = "literalTarget"
