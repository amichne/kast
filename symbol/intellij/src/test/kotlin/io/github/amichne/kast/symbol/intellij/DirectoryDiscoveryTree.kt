package io.github.amichne.kast.symbol.intellij

/** Immutable source and independent declaration oracle shared by directory proof cases. */
internal object DirectoryDiscoveryTree {
    const val MAIN = "app/src/main/kotlin"
    const val TEST = "app/src/test/kotlin"
    const val SELECTED = "$MAIN/selected"
    val sources =
        linkedMapOf(
            "$SELECTED/Target.kt" to "class Target",
            "$SELECTED/Helpers.kt" to "fun helper() = 1\nval property = 2\ntypealias Alias = Target",
            "$SELECTED/nested/TargetChild.kt" to "class TargetChild",
            "$MAIN/unrelated/Target.kt" to "class Target",
            "$TEST/selected/TestTarget.kt" to "class TestTarget",
            "$MAIN/selected-other/Target.kt" to "class Target",
        )

    data class Declaration(val name: String, val path: String, val start: Int, val end: Int)

    val direct =
        listOf(
            Declaration("Target", "$SELECTED/Target.kt", 0, 12),
            Declaration("helper", "$SELECTED/Helpers.kt", 0, 16),
            Declaration("property", "$SELECTED/Helpers.kt", 17, 33),
            Declaration("Alias", "$SELECTED/Helpers.kt", 34, 58),
        )
    val descendants = direct + Declaration("TargetChild", "$SELECTED/nested/TargetChild.kt", 0, 17)
}
