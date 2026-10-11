package io.github.amichne.kast.symbol.intellij

/** Independently specified declarations; syntax/recovery authority comes from the real bundled parser. */
internal object ClasslikeAstDifferentialCases {
    data class Case(val label: String, val source: String, val names: List<String?>)

    val values =
        listOf(
            Case("ordinary", "class Target", listOf("Target")),
            Case("interface", "interface Target", listOf("Target")),
            Case("enum", "enum class Target { ENTRY { class InsideEntry } }", listOf("Target", "InsideEntry")),
            Case("enum recovery", "enum Target { ENTRY }", listOf("Target")),
            Case(
                "modifiers",
                "data class Data(val n: Int)\n@JvmInline value class Value(val n: Int)\nannotation class Annotation",
                listOf("Data", "Value", "Annotation"),
            ),
            Case(
                "objects and companions",
                "object Named\nclass Outer { companion object { class Inside } }\n" +
                    "class Other { companion object Explicit }",
                listOf("Named", "Outer", "Companion", "Inside", "Other", "Explicit"),
            ),
            Case("object expression", "val value = object { class Nested }", listOf(null, "Nested")),
            Case("function body", "fun outer() { class Local; fun inner() { class Deep } }", listOf("Local", "Deep")),
            Case("property body", "val v = run { class Local; 1 }", listOf("Local")),
            Case(
                "accessors",
                "val v: Int get() { class Getter; return 0 }\nvar w: Int = 0\nset(value) { class Setter }",
                listOf("Getter", "Setter"),
            ),
            Case(
                "constructor and init",
                "class Outer { init { class InitLocal }; constructor(n: Int) { class ConstructorLocal } }",
                listOf("Outer", "InitLocal", "ConstructorLocal"),
            ),
            Case(
                "enum entry body",
                "enum class Outer { ENTRY { fun f() { class BodyLocal } } }",
                listOf("Outer", "BodyLocal"),
            ),
            Case("backtick unicode", "class `class Target`\nclass Δείγμα", listOf("class Target", "Δείγμα")),
            Case(
                "annotation expression",
                "@Anno(run { class AnnotationLocal; 0 }) class Outer",
                listOf("Outer", "AnnotationLocal"),
            ),
            Case("type annotation", "val x: @Anno(run { class TypeLocal; 0 }) Int = 0", listOf("TypeLocal")),
            Case("string interpolation", "val x = \"\${run { class StringLocal; 0 }}\"", listOf("StringLocal")),
            Case("comments strings", "/** class NotCode */\nval x = \"enum NotCode\"\nclass Target", listOf("Target")),
            Case("error body", "fun outer() { !!!; class Local }", listOf("Local")),
            Case("missing name", "class { class Nested }", listOf(null, "Nested")),
            Case("unterminated", "class Outer { val x = run { class Inner", listOf("Outer", "Inner")),
            Case("function type", "val x: (@Anno(run { class Embedded; 0 }) Int) -> Int = { it }", listOf("Embedded")),
            Case("dense target", ClasslikeAstDispatcherFixture.denseSources().getValue("Target.kt"), listOf("Target")),
            Case("dense wrapper", ClasslikeAstDispatcherFixture.denseSources().getValue("Uses.kt"), listOf("Uses")),
        )
}
