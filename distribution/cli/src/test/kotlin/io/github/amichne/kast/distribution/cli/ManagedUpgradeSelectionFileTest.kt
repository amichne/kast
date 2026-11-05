package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedUpgradeSelectionFileTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `aliased temporary directory produces a physical captured selection path`() {
        val root = temporary.toRealPath()
        val physical = Files.createDirectory(root.resolve("physical temporary directory"))
        val alias = Files.createSymbolicLink(root.resolve("selected temporary directory"), physical)
        val selected = createCapturedSelectionFile(alias)
        try {
            assertEquals(selected.toRealPath(), selected)
            assertEquals(physical, selected.parent)
            assertTrue(Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS))
        } finally {
            Files.delete(selected)
        }
        assertTrue(Files.isSymbolicLink(alias))
        assertTrue(Files.isDirectory(physical))
    }
}
