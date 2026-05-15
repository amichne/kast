package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.metrics.general.FileFilterSpec
import io.github.amichne.kast.indexstore.api.metrics.general.filterByPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileFilterSpecTest {

    @Test
    fun `empty filter matches any non-null path`() {
        val filter = FileFilterSpec()
        assertTrue(filter.isEmpty)
        assertTrue(filter.matches("/src/main/Foo.kt"))
        assertTrue(filter.matches("/test/BarTest.kt"))
    }

    @Test
    fun `empty filter does not match null path`() {
        val filter = FileFilterSpec()
        assertFalse(filter.matches(null))
    }

    @Test
    fun `folderPrefix filters by path prefix`() {
        val filter = FileFilterSpec(folderPrefix = "/src/main")
        assertTrue(filter.matches("/src/main/Foo.kt"))
        assertTrue(filter.matches("/src/main/sub/Bar.kt"))
        assertFalse(filter.matches("/src/test/FooTest.kt"))
        assertFalse(filter.matches("/other/Foo.kt"))
        // paths sharing a prefix of the folder name but not the separator must not match
        assertFalse(filter.matches("/src/mainlib/Foo.kt"))
        // the folder path itself is not a file path, must not match
        assertFalse(filter.matches("/src/main"))
    }

    @Test
    fun `folderPrefix with trailing slash still matches`() {
        val filter = FileFilterSpec(folderPrefix = "/src/main/")
        assertTrue(filter.matches("/src/main/Foo.kt"))
        assertFalse(filter.matches("/src/test/Foo.kt"))
    }

    @Test
    fun `fileGlob filters by glob pattern`() {
        val filter = FileFilterSpec(fileGlob = "**/*Test.kt")
        assertTrue(filter.matches("/src/test/FooTest.kt"))
        assertTrue(filter.matches("/src/test/sub/BarTest.kt"))
        assertFalse(filter.matches("/src/main/Foo.kt"))
    }

    @Test
    fun `fileGlob and folderPrefix combine with AND semantics`() {
        val filter = FileFilterSpec(fileGlob = "**/*Test.kt", folderPrefix = "/src/test")
        assertTrue(filter.matches("/src/test/FooTest.kt"))
        assertFalse(filter.matches("/src/main/FooTest.kt"))  // fails folderPrefix
        assertFalse(filter.matches("/src/test/Foo.kt"))       // fails fileGlob
    }

    @Test
    fun `filterByPath extension applies filter to list`() {
        val paths = listOf("/src/main/Foo.kt", "/src/test/FooTest.kt", "/src/main/Bar.kt")
        val filter = FileFilterSpec(folderPrefix = "/src/main")
        val result = paths.filterByPath(filter) { it }
        assertEquals(listOf("/src/main/Foo.kt", "/src/main/Bar.kt"), result)
    }

    @Test
    fun `filterByPath returns full list when filter is empty`() {
        val paths = listOf("/a.kt", "/b.kt")
        val result = paths.filterByPath(FileFilterSpec()) { it }
        assertEquals(paths, result)
    }
}
