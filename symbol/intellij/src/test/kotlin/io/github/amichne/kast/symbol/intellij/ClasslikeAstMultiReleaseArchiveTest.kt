package io.github.amichne.kast.symbol.intellij

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Owned JAR and real JDK application loader only; no product, IDEA, native query or inference launch. */
class ClasslikeAstMultiReleaseArchiveTest {
    @Test
    fun `multi release archive cannot attest loaded bytes by hashing its unchanged base entry`(@TempDir home: Path) {
        val original = compileWitness(home)
        val archive = archive(home, original, multiRelease = true)
        assertEquals(listOf("modified", APPLICATION_LOADER), applicationOutput(home, archive))
        JarFile(archive.toFile()).use { jar ->
            assertTrue(jar.isMultiRelease)
            assertEquals(digest(original), baseDigest(jar))
        }
        var inspections = 0
        val result =
            IntellijKotlinAstSourceBinding.inspectArchive(archive, { true }) { jar ->
                inspections++
                assertEquals(digest(original), baseDigest(jar))
                KotlinAstSourceBinding.SUPPORTED
            }
        assertEquals(KotlinAstSourceBinding.UNSUPPORTED, result)
        assertEquals(0, inspections, "versioned definitions must be rejected before base-byte qualification")
    }

    @Test
    fun `ordinary archive retains application loader and base entry qualification`(@TempDir home: Path) {
        val original = compileWitness(home)
        val archive = archive(home, original, multiRelease = false)
        assertEquals(listOf("original", APPLICATION_LOADER), applicationOutput(home, archive))
        var inspections = 0
        val result =
            IntellijKotlinAstSourceBinding.inspectArchive(archive, { true }) { jar ->
                inspections++
                assertFalse(jar.isMultiRelease)
                assertEquals(digest(original), baseDigest(jar))
                KotlinAstSourceBinding.SUPPORTED
            }
        assertEquals(KotlinAstSourceBinding.SUPPORTED, result)
        assertEquals(1, inspections)
    }

    private fun compileWitness(home: Path): ByteArray {
        val source = home.resolve("ArchiveWitness.java")
        Files.writeString(
            source,
            """
            package fixture;
            public final class ArchiveWitness {
                public static void main(String[] args) {
                    System.out.println("original");
                    System.out.println(ArchiveWitness.class.getClassLoader().getClass().getName());
                }
            }
            """
                .trimIndent(),
        )
        val output = ByteArrayOutputStream()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, output, output, "-d", home.toString(), source.toString()), output.toString())
        return Files.readAllBytes(home.resolve(CLASS_ENTRY))
    }

    private fun archive(home: Path, original: ByteArray, multiRelease: Boolean): Path {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MULTI_RELEASE] = multiRelease.toString()
        val archive = home.resolve("owned archive.jar")
        JarOutputStream(Files.newOutputStream(archive), manifest).use { jar ->
            jar.putNextEntry(JarEntry(CLASS_ENTRY))
            jar.write(original)
            jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/versions/${Runtime.version().feature()}/$CLASS_ENTRY"))
            jar.write(
                String(original, Charsets.ISO_8859_1).replace("original", "modified").toByteArray(Charsets.ISO_8859_1)
            )
            jar.closeEntry()
        }
        return archive
    }

    private fun applicationOutput(home: Path, archive: Path): List<String> {
        val output = home.resolve("application-output.txt")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val child =
            ProcessBuilder(java.toString(), "-cp", archive.toString(), "fixture.ArchiveWitness")
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "owned application loader witness must terminate")
            assertEquals(0, child.exitValue(), Files.readString(output))
            return Files.readAllLines(output)
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "owned witness cleanup must terminate")
            }
        }
    }

    private fun baseDigest(jar: JarFile): List<Byte> =
        jar.getInputStream(requireNotNull(jar.getJarEntry(CLASS_ENTRY))).use { digest(it.readBytes()) }

    private fun digest(bytes: ByteArray): List<Byte> = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

    companion object {
        private const val CLASS_ENTRY = "fixture/ArchiveWitness.class"
        private const val APPLICATION_LOADER = "jdk.internal.loader.ClassLoaders\$AppClassLoader"
    }
}
