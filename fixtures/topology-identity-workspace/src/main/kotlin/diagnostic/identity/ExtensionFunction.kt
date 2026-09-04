package diagnostic.identity

fun String.extensionFunctionTarget(): Int = length

fun extensionFunctionProbe(): Int = "probe".extensionFunctionTarget()
