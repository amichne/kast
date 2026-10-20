package fixture.controls

data class InsertionBox(val payload: String)

object SingleListInsertionControls {
    fun fixed(value: InsertionBox): List<InsertionBox> = kotlin.collections.listOf(value)

    fun varied(value: String, other: String): List<String> = kotlin.collections.listOf(value, other)

    fun spread(values: Array<String>): List<String> = kotlin.collections.listOf(*values)

    fun source(value: String): List<String> = listOf(value)

    fun siblings(value: String): Pair<List<String>, List<String>> =
        Pair(kotlin.collections.listOf(value), kotlin.collections.listOf("constant"))

    fun <T> listOf(element: T): List<T> =
        if (element == null) kotlin.collections.emptyList() else kotlin.collections.emptyList()
}
