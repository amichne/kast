import conventions.jsoncontracts.KotlinDocumentationScan
import conventions.jsoncontracts.KotlinDocumentationScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinDocumentationScannerTest {
    @Test
    fun `nested declaration identity distinguishes identical member signatures`() {
        val declarations = scan(
            """
            class First {
                /** Reads first. */
                fun read(): String = "first"
            }
            class Second {
                /** Reads second. */
                fun read(): String = "second"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("First", "First.read", "Second", "Second.read"), declarations.map { it.declarationPath })
        assertEquals(2, declarations.count { it.signature == "fun read(): String" })
    }

    @Test
    fun `private and internal owners hide their descendants`() {
        val declarations = scan(
            """
            private class Hidden {
                fun leaked(): String = "no"
            }
            internal object InternalOwner {
                val leaked: String = "no"
            }
            class Visible {
                fun kept(): String = "yes"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Visible", "Visible.kept"), declarations.map { it.declarationPath })
    }

    @Test
    fun `KDoc strips comment syntax but preserves authored code indentation`() {
        val declaration = scan(
            """
            /**
             * Summary.
             *
             * ```kotlin
             * fun example() {
             *     println("kept")
             * }
             * ```
             */
            fun documented() = Unit
            """.trimIndent(),
        ).single()

        assertTrue(declaration.documentation.startsWith("Summary.\n\n```kotlin\nfun example()"))
        assertTrue(declaration.documentation.contains("\n    println(\"kept\")\n"))
        assertFalse(declaration.documentation.contains("/**"))
        assertFalse(declaration.documentation.contains("*/"))
    }

    @Test
    fun `source headers retain constraints constructor visibility and literal whitespace`() {
        val declarations = scan(
            """
            @Marker("two  spaces")
            class Box<T> private constructor(val value: T) where T : CharSequence {
                fun body() = Unit
            }
            fun <T> T.`keep spaces`(label: String = "two  spaces"): T where T : CharSequence = this
            """.trimIndent(),
        )
        assertEquals(
            "@Marker(\"two  spaces\")\nclass Box<T> private constructor(val value: T) where T : CharSequence",
            declarations.single { it.name == "Box" }.signature,
        )
        assertEquals(
            "fun <T> T.`keep spaces`(label: String = \"two  spaces\"): T where T : CharSequence",
            declarations.single { it.name == "keep spaces" }.signature,
        )
    }

    @Test
    fun `initializer locals and anonymous object members are excluded`() {
        val declarations = scan(
            """
            val created = run {
                class InitializerLocal { fun leaked() = Unit }
                InitializerLocal()
            }
            val anonymous = object { val leaked = 1 }
            class Visible {
                init { class InitLocal }
                fun kept() = Unit
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Visible", "Visible.kept", "anonymous", "created"), declarations.map { it.declarationPath })
    }

    @Test
    fun `duplicate syntax is retained for downstream identity rejection`() {
        val declarations = scan("fun duplicate() = Unit\nfun duplicate() = Unit")
        assertEquals(2, declarations.size)
    }

    private fun scan(source: String) =
        KotlinDocumentationScanner().use { scanner ->
            when (val result = scanner.scan("Fixture.kt", source)) {
                is KotlinDocumentationScan.Accepted -> result.declarations
                is KotlinDocumentationScan.Rejected -> error("scan rejected: ${result.reason}")
            }
        }
}
