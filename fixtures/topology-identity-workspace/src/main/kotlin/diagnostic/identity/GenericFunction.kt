package diagnostic.identity

fun <Value> genericTarget(value: Value): Value = value

fun genericFunctionProbe(): String = genericTarget("probe")
