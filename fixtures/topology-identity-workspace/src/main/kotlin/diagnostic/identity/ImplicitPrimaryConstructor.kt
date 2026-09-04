package diagnostic.identity

class ImplicitPrimaryTarget(val value: Int)

fun implicitPrimaryConstructorProbe(): ImplicitPrimaryTarget = ImplicitPrimaryTarget(1)
