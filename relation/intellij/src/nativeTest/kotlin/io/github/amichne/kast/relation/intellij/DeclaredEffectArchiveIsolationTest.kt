package io.github.amichne.kast.relation.intellij

import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase

class DeclaredEffectArchiveIsolationTest : TestCase() {
    fun testCleanupPreservesOtherCopiesAndPreparedArchive() {
        val prepared = Path.of(checkNotNull(System.getProperty("kast.declaredEffectArchive")))
        val original = Files.readAllBytes(prepared)
        DeclaredEffectArchive.compile().use { second ->
            DeclaredEffectArchive.compile().use { first ->
                assertTrue(first.root != second.root)
                assertTrue(original.contentEquals(Files.readAllBytes(first.jar)))
                assertTrue(original.contentEquals(Files.readAllBytes(second.jar)))
                Files.write(first.jar, byteArrayOf(0))
            }
            assertTrue(original.contentEquals(Files.readAllBytes(second.jar)))
            assertTrue(original.contentEquals(Files.readAllBytes(prepared)))
        }
        assertTrue(original.contentEquals(Files.readAllBytes(prepared)))
    }
}
