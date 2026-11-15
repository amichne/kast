import conventions.jsoncontracts.KnowledgeDocsFailureCode
import conventions.jsoncontracts.KotlinSymbolScan
import conventions.jsoncontracts.KotlinDocumentationScanner
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotlinDocumentationScannerTest {
    @Test
    fun `knowledge citations resolve actual declarations regardless of visibility or layout`() {
        KotlinDocumentationScanner().use { scanner ->
            val source = """
                // class CommentOnly
                val quoted = "class StringOnly"
                private class Hidden
                internal object Owner { fun member() = Unit }
                fun <T> T.
                    multiline(): T = this
                typealias Alias = Hidden
            """.trimIndent()
            val result = assertInstanceOf(KotlinSymbolScan.Accepted::class.java, scanner.symbols("Example.kt", source))
            assertEquals(listOf("Alias", "Hidden", "Owner", "T", "member", "multiline", "quoted"), result.names)
            assertEquals(3, result.declarations.single { it.name == "Hidden" }.startLine)
            assertEquals(6, result.declarations.single { it.name == "multiline" }.startLine)
            assertEquals(6, result.declarations.single { it.name == "multiline" }.endLine)
            assertEquals(
                KotlinSymbolScan.Rejected(KnowledgeDocsFailureCode.INVALID_KOTLIN),
                scanner.symbols("Broken.kt", "class Broken {"),
            )
        }
    }

    @Test
    fun `test witness requires annotated function with body`() {
        KotlinDocumentationScanner().use { scanner ->
            val source = """
                class Example
                @Test fun behavior() = Unit
                @Test fun missing()
                // @Test fun comment() = Unit
                val quoted = "@Test fun ghost() = Unit"
            """.trimIndent()
            val result = assertInstanceOf(KotlinSymbolScan.Accepted::class.java, scanner.symbols("Example.kt", source))
            assertEquals(listOf("behavior"), result.declarations.filter { it.executableTest }.map { it.name })
            assertEquals(2, result.declarations.single { it.name == "behavior" }.startLine)
        }
    }
}
