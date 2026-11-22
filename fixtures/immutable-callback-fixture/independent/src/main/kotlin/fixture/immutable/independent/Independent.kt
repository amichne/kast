package fixture.immutable.independent

fun independentTarget(): String = "independent"
fun independentWrapper(block: () -> String): String = block()
fun independentEntry(): String = independentWrapper { independentTarget() }
