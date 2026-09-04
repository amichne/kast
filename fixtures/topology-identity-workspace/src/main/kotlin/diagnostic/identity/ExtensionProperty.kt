package diagnostic.identity

val String.extensionPropertyTarget: Int
    get() = length

fun extensionPropertyProbe(): Int = "probe".extensionPropertyTarget
