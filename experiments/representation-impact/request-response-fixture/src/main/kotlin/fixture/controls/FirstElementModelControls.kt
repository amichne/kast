package fixture.controls.firstelement

fun List<String>.firstOrNull(): String? = "constant-first"

fun sourceDefinedFirstOrNull(values: List<String>): String? = values.firstOrNull()
