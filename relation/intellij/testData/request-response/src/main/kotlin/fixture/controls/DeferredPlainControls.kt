package fixture.controls.deferred

class Request(val destination: String)

fun lambdaRequest(): Request {
    val create = { Request("nested") }
    return create()
}

fun anonymousRequest(): Request {
    val create = fun() = Request("nested")
    return create()
}

sealed interface Choice

class Selected(val destination: String) : Choice

fun Choice.destination(): String = when (this) {
    is Selected -> destination
    else -> "constant"
}
