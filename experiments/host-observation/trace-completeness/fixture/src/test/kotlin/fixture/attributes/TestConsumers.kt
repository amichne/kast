package fixture.attributes
fun testAttributes(manager: Attributes, key: AttributeKey<String>): String =
    getWithAttributes(manager, key, load = { "test" })
