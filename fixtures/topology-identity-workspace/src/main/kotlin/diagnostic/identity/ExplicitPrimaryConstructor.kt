package diagnostic.identity

class ExplicitPrimaryTarget constructor(val value: Int)

fun explicitPrimaryConstructorProbe(): ExplicitPrimaryTarget = ExplicitPrimaryTarget(1)
