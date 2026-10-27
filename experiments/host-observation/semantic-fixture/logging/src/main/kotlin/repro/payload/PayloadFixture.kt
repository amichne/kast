package repro.payload

fun payloadTarget(value: String): String = value
fun payloadTarget(value: Int): Int = value
fun payloadCaller(): String = payloadTarget("expected")
fun payloadOuter(): String = payloadCaller()
