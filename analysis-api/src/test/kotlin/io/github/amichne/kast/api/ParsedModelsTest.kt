package io.github.amichne.kast.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParsedModelsTest {

    @Test
    fun `FilePosition parsed validates path and offset`() {
        val fp = FilePosition(filePath = "/workspace/src/Main.kt", offset = 42)
        val parsed = fp.parsed()
        assertEquals(42, parsed.offset.value)
        assert(parsed.filePath.value.endsWith("Main.kt"))
    }

    @Test
    fun `FilePosition parsed rejects relative path`() {
        val fp = FilePosition(filePath = "relative/path.kt", offset = 0)
        assertThrows<ValidationException> { fp.parsed() }
    }

    @Test
    fun `FilePosition parsed rejects negative offset`() {
        val fp = FilePosition(filePath = "/workspace/src/Main.kt", offset = -1)
        assertThrows<IllegalArgumentException> { fp.parsed() }
    }

    @Test
    fun `Location parsed validates all fields`() {
        val loc = Location(
            filePath = "/workspace/src/Main.kt",
            startOffset = 10,
            endOffset = 20,
            startLine = 1,
            startColumn = 5,
            preview = "fun main()",
        )
        val parsed = loc.parsed()
        assertEquals(10, parsed.startOffset.value)
        assertEquals(20, parsed.endOffset.value)
        assertEquals(1, parsed.startLine.value)
        assertEquals(5, parsed.startColumn.value)
        assertEquals("fun main()", parsed.preview)
    }

    @Test
    fun `Location parsed rejects zero startLine`() {
        val loc = Location(
            filePath = "/workspace/src/Main.kt",
            startOffset = 0,
            endOffset = 5,
            startLine = 0,
            startColumn = 1,
            preview = "test",
        )
        assertThrows<IllegalArgumentException> { loc.parsed() }
    }

    @Test
    fun `TextEdit parsed validates path and offsets`() {
        val edit = TextEdit(
            filePath = "/workspace/src/Main.kt",
            startOffset = 5,
            endOffset = 10,
            newText = "newValue",
        )
        val parsed = edit.parsed()
        assertEquals(5, parsed.startOffset.value)
        assertEquals(10, parsed.endOffset.value)
        assertEquals("newValue", parsed.newText)
    }

    @Test
    fun `TextEdit parsed rejects relative path`() {
        val edit = TextEdit(
            filePath = "relative/Main.kt",
            startOffset = 0,
            endOffset = 5,
            newText = "x",
        )
        assertThrows<ValidationException> { edit.parsed() }
    }
}
