package repro.payload.other

fun payloadTarget(value: String): String = value
fun payloadUnrelated(): String = payloadTarget("control")
