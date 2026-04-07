package io.github.amichne.kast.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CoreTypesTest {

    @TempDir
    lateinit var tempDir: Path

    // --- NormalizedPath ---

    @Test
    fun `NormalizedPath of resolves existing path to absolute canonical form`() {
        val file = Files.createFile(tempDir.resolve("test.kt"))
        val normalized = NormalizedPath.of(file)
        assertTrue(Path.of(normalized.value).isAbsolute)
        assertEquals(file.toRealPath().normalize().toString(), normalized.value)
    }

    @Test
    fun `NormalizedPath of resolves symlinks`() {
        val realFile = Files.createFile(tempDir.resolve("real.kt"))
        val link = tempDir.resolve("link.kt")
        Files.createSymbolicLink(link, realFile)

        val fromReal = NormalizedPath.of(realFile)
        val fromLink = NormalizedPath.of(link)
        assertEquals(fromReal, fromLink)
    }

    @Test
    fun `NormalizedPath of handles non-existent file under existing parent`() {
        val nonExistent = tempDir.resolve("does-not-exist.kt")
        val normalized = NormalizedPath.of(nonExistent)
        assertTrue(Path.of(normalized.value).isAbsolute)
        assertTrue(normalized.value.endsWith("does-not-exist.kt"))
    }

    @Test
    fun `NormalizedPath of normalizes dotdot segments`() {
        val subDir = Files.createDirectory(tempDir.resolve("sub"))
        val withDots = subDir.resolve("../sub/file.kt")
        val direct = subDir.resolve("file.kt")
        assertEquals(NormalizedPath.of(direct), NormalizedPath.of(withDots))
    }

    @Test
    fun `NormalizedPath ofAbsolute does not resolve symlinks`() {
        val realFile = Files.createFile(tempDir.resolve("real.kt"))
        val link = tempDir.resolve("link.kt")
        Files.createSymbolicLink(link, realFile)

        val fromLink = NormalizedPath.ofAbsolute(link)
        // ofAbsolute preserves the link path, unlike of()
        assertTrue(fromLink.value.contains("link.kt"))
    }

    @Test
    fun `NormalizedPath parse rejects relative path`() {
        val ex = assertThrows<ValidationException> {
            NormalizedPath.parse("relative/path.kt")
        }
        assertTrue(ex.message.contains("absolute"))
    }

    @Test
    fun `NormalizedPath parse normalizes absolute path`() {
        val raw = tempDir.resolve("sub/../file.kt").toString()
        val parsed = NormalizedPath.parse(raw)
        val expected = tempDir.resolve("file.kt").toAbsolutePath().normalize().toString()
        assertEquals(expected, parsed.value)
    }

    @Test
    fun `NormalizedPath ofNormalized wraps without processing`() {
        val raw = "/already/normalized/path.kt"
        val wrapped = NormalizedPath.ofNormalized(raw)
        assertEquals(raw, wrapped.value)
    }

    @Test
    fun `NormalizedPath toJavaPath round-trips`() {
        val file = Files.createFile(tempDir.resolve("round.kt"))
        val normalized = NormalizedPath.of(file)
        assertEquals(normalized.value, normalized.toJavaPath().toString())
    }

    @Test
    fun `NormalizedPath compareTo sorts lexicographically`() {
        val a = NormalizedPath.ofNormalized("/a/path.kt")
        val b = NormalizedPath.ofNormalized("/b/path.kt")
        assertTrue(a < b)
        assertTrue(b > a)
        assertEquals(0, a.compareTo(a))
    }

    @Test
    fun `NormalizedPath equality is value-based`() {
        val first = NormalizedPath.ofNormalized("/same/path.kt")
        val second = NormalizedPath.ofNormalized("/same/path.kt")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, NormalizedPath.ofNormalized("/different.kt"))
    }

    @Test
    fun `NormalizedPath works as map key`() {
        val path = NormalizedPath.ofNormalized("/some/file.kt")
        val map = mapOf(path to "value")
        assertEquals("value", map[NormalizedPath.ofNormalized("/some/file.kt")])
    }

    // --- KotlinIdentifier ---

    @Test
    fun `KotlinIdentifier wraps and unwraps`() {
        val id = KotlinIdentifier("myFunction")
        assertEquals("myFunction", id.value)
        assertEquals("myFunction", id.toString())
    }

    @Test
    fun `KotlinIdentifier equality is value-based`() {
        assertEquals(KotlinIdentifier("foo"), KotlinIdentifier("foo"))
        assertNotEquals(KotlinIdentifier("foo"), KotlinIdentifier("bar"))
    }

    // --- ModuleName ---

    @Test
    fun `ModuleName wraps and unwraps`() {
        val name = ModuleName("app.main")
        assertEquals("app.main", name.value)
    }

    // --- PackageName ---

    @Test
    fun `PackageName wraps and unwraps`() {
        val pkg = PackageName("io.github.amichne.kast")
        assertEquals("io.github.amichne.kast", pkg.value)
    }

    // --- FqName ---

    @Test
    fun `FqName wraps and unwraps`() {
        val fqn = FqName("io.github.amichne.kast.api.NormalizedPath")
        assertEquals("io.github.amichne.kast.api.NormalizedPath", fqn.value)
    }

    // --- ByteOffset ---

    @Test
    fun `ByteOffset accepts zero`() {
        assertEquals(0, ByteOffset(0).value)
    }

    @Test
    fun `ByteOffset accepts positive`() {
        assertEquals(42, ByteOffset(42).value)
    }

    @Test
    fun `ByteOffset rejects negative`() {
        assertThrows<IllegalArgumentException> {
            ByteOffset(-1)
        }
    }

    @Test
    fun `ByteOffset compareTo works`() {
        assertTrue(ByteOffset(0) < ByteOffset(1))
        assertEquals(0, ByteOffset(5).compareTo(ByteOffset(5)))
    }

    // --- LineNumber ---

    @Test
    fun `LineNumber accepts 1`() {
        assertEquals(1, LineNumber(1).value)
    }

    @Test
    fun `LineNumber rejects 0`() {
        assertThrows<IllegalArgumentException> {
            LineNumber(0)
        }
    }

    @Test
    fun `LineNumber rejects negative`() {
        assertThrows<IllegalArgumentException> {
            LineNumber(-1)
        }
    }

    // --- ColumnNumber ---

    @Test
    fun `ColumnNumber accepts 1`() {
        assertEquals(1, ColumnNumber(1).value)
    }

    @Test
    fun `ColumnNumber rejects 0`() {
        assertThrows<IllegalArgumentException> {
            ColumnNumber(0)
        }
    }

    @Test
    fun `ColumnNumber rejects negative`() {
        assertThrows<IllegalArgumentException> {
            ColumnNumber(-1)
        }
    }

    // --- ShaHash ---

    @Test
    fun `Sha256Hash wraps and unwraps`() {
        val hash = ShaHash("abc123def456")
        assertEquals("abc123def456", hash.value)
    }

    // --- CacheSchemaVersion ---

    @Test
    fun `CacheSchemaVersion wraps and unwraps`() {
        val version = CacheSchemaVersion(2)
        assertEquals(2, version.value)
    }
}
