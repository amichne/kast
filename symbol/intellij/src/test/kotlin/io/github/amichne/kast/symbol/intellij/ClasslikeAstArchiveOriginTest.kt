package io.github.amichne.kast.symbol.intellij

import com.intellij.util.lang.PathClassLoader
import com.intellij.util.lang.UrlClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ClasslikeAstArchiveOriginTest {
    @Test
    fun `IDEA URL loader origin binds its own archive despite null code source`(@TempDir home: Path) {
        val archive = archive(home)
        val parent =
            object : ClassLoader(null) {
                override fun getResource(name: String) = throw AssertionError("parent resource delegation is unproven")
            }
        val loader = UrlClassLoader.build().files(listOf(archive)).parent(parent).get()
        val witness = loader.loadClass(ClasslikeAstOriginWitness::class.java.name)
        assertSame(loader, witness.classLoader)
        assertNull(witness.protectionDomain.codeSource?.location)
        val origin = requireNotNull(IntellijClassArchiveOrigin.resolve(witness))
        assertEquals(archive, origin.archive)
        assertEquals(
            setOf(loader.javaClass, UrlClassLoader::class.java, com.intellij.util.lang.ClassPath::class.java),
            origin.loaderWitnesses,
        )
    }

    @Test
    fun `IDEA path loader without transformer binds the same owned class archive`(@TempDir home: Path) {
        val archive = archive(home)
        val loader = PathClassLoader(UrlClassLoader.build().files(listOf(archive)))
        val witness = loader.loadClass(ClasslikeAstOriginWitness::class.java.name)
        assertSame(loader, witness.classLoader)
        assertNull(witness.protectionDomain.codeSource?.location)
        assertEquals(archive, IntellijClassArchiveOrigin.resolve(witness)?.archive)
    }

    @Test
    fun `active IDEA path transformer stays unproven even when returning original bytes`(@TempDir home: Path) {
        val archive = archive(home)
        val loader = PathClassLoader(UrlClassLoader.build().files(listOf(archive)))
        loader.setTransformer(
            object : PathClassLoader.BytecodeTransformer {
                override fun isApplicable(name: String, loader: ClassLoader): Boolean = true

                override fun transform(loader: ClassLoader, name: String, bytes: ByteArray): ByteArray = bytes
            }
        )
        val witness = loader.loadClass(ClasslikeAstOriginWitness::class.java.name)
        assertSame(loader, witness.classLoader)
        assertNull(IntellijClassArchiveOrigin.resolve(witness))
    }

    @Test
    fun `unknown URL loader implementation retains unsupported origin`() {
        val loader = object : UrlClassLoader(UrlClassLoader.build().files(emptyList()), null, true) {}
        val bytes =
            requireNotNull(ClasslikeAstOriginWitness::class.java.getResourceAsStream("ClasslikeAstOriginWitness.class"))
                .use { it.readBytes() }
        val witness = loader.consumeClassData(ClasslikeAstOriginWitness::class.java.name, bytes)
        assertSame(loader, witness.classLoader)
        assertNull(IntellijClassArchiveOrigin.resolve(witness))
    }

    private fun archive(home: Path): Path {
        val owner = ClasslikeAstOriginWitness::class.java
        val bytes = requireNotNull(owner.getResourceAsStream("ClasslikeAstOriginWitness.class")).use { it.readBytes() }
        val archive = home.resolve("owned witness #.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { output ->
            output.putNextEntry(JarEntry(owner.name.replace('.', '/') + ".class"))
            output.write(bytes)
            output.closeEntry()
        }
        return archive
    }
}

/** Pure classloading witness; no product or IDE services. */
class ClasslikeAstOriginWitness
