package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class VerbosePathsTest {
    private val root: Path = Path.of("/project")

    private fun loc(filePath: String, line: Int): Location =
        Location(filePath = filePath, startOffset = 0, endOffset = 10, startLine = line, startColumn = 1, preview = "")

    @Test
    fun `short path uses bare filename plus line number`() {
        val result = Paths.locationLine(root, loc("/project/src/main/kotlin/Foo.kt", 42), verbose = false)
        assertEquals("Foo.kt:42", result)
    }

    @Test
    fun `verbose path uses workspace-relative path plus line number`() {
        val result = Paths.locationLine(root, loc("/project/src/main/kotlin/Foo.kt", 42), verbose = true)
        assertEquals("src/main/kotlin/Foo.kt:42", result)
    }

    @Test
    fun `renderCallTreePreview uses short names by default`() {
        val tree = CallNode(
            symbol = Symbol(
                fqName = "com.example.Foo.bar",
                kind = SymbolKind.FUNCTION,
                location = loc("/project/src/main/kotlin/Foo.kt", 10),
            ),
            children = listOf(
                CallNode(
                    symbol = Symbol(
                        fqName = "com.example.Baz.qux",
                        kind = SymbolKind.FUNCTION,
                        location = loc("/project/src/main/kotlin/Baz.kt", 20),
                    ),
                    children = emptyList(),
                ),
            ),
        )

        val lines = renderCallTreePreview(root, tree, verbose = false)
        assertTrue(lines[0].contains("Foo.kt:10"), "expected short path in root: ${lines[0]}")
        assertTrue(lines[1].contains("Baz.kt:20"), "expected short path in child: ${lines[1]}")
        assertTrue(!lines[0].contains("src/main/kotlin"), "should NOT contain relative path: ${lines[0]}")
    }

    @Test
    fun `renderCallTreePreview uses relative paths when verbose`() {
        val tree = CallNode(
            symbol = Symbol(
                fqName = "com.example.Foo.bar",
                kind = SymbolKind.FUNCTION,
                location = loc("/project/src/main/kotlin/Foo.kt", 10),
            ),
            children = emptyList(),
        )

        val lines = renderCallTreePreview(root, tree, verbose = true)
        assertTrue(lines[0].contains("src/main/kotlin/Foo.kt:10"), "expected verbose path: ${lines[0]}")
    }
}
