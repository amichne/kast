package io.github.amichne.kast.runtime.hosted

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.KotlinClassFinder
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** Characterizes binary-cache provenance. These cases do not resolve a source occurrence or produce a snapshot. */
class NativeHistoricalBinaryCacheTest : NativeSmartModeFixtureTest() {
    fun testSuppliedBytesDoNotReplaceTheSameFileAndStampCache() {
        val original = classBytes("A")
        val replacement = classBytes("B")
        val file = classFile()
        val stamp = file.modificationStamp
        assertFalse(original.contentEquals(replacement))

        ReadAction.run<RuntimeException> {
            assertNotNull(ApplicationManager.getApplication().getService(KotlinBinaryClassCache::class.java))
            val first = cachedContent(file, original)
            val second = cachedContent(file, replacement)

            assertTrue(original.contentEquals(first.content))
            assertSame("The existing cache wins before the supplied bytes are read", first, second)
            assertTrue(original.contentEquals(second.content))
            assertFalse(replacement.contentEquals(second.content))
            assertEquals(stamp, file.modificationStamp)
        }
    }

    fun testFreshFileIdentityReadsNewBytesAtTheSameStamp() {
        val original = classBytes("A")
        val replacement = classBytes("B")
        val firstFile = classFile()
        val freshFile = classFile()
        assertNotSame(firstFile, freshFile)
        assertEquals(firstFile.path, freshFile.path)
        assertEquals(firstFile.modificationStamp, freshFile.modificationStamp)

        ReadAction.run<RuntimeException> {
            val first = cachedContent(firstFile, original)
            val fresh = cachedContent(freshFile, replacement)

            assertTrue(original.contentEquals(first.content))
            assertNotSame(first, fresh)
            assertTrue(replacement.contentEquals(fresh.content))
            assertFalse(original.contentEquals(fresh.content))
        }
    }

    private fun classFile() = LightVirtualFile("CacheInput.class", JavaClassFileType.INSTANCE, "", 1L)

    private fun cachedContent(file: LightVirtualFile, bytes: ByteArray): KotlinClassFinder.Result.ClassFileContent {
        val result =
            KotlinBinaryClassCache.getKotlinBinaryClassOrClassFileContent(
                file,
                MetadataVersion.INSTANCE,
                fileContent = bytes,
            )
        return when (result) {
            is KotlinClassFinder.Result.ClassFileContent -> result
            is KotlinClassFinder.Result.KotlinClass -> error("The Java fixture must not contain Kotlin metadata")
            null -> error("The valid Java class fixture must produce class content")
        }
    }

    private fun classBytes(version: String): ByteArray =
        ClassWriter(0).run {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
                "historical/CacheInput",
                null,
                "java/lang/Object",
                null,
            )
            visitField(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    "VERSION",
                    "Ljava/lang/String;",
                    null,
                    version,
                )
                .visitEnd()
            visitEnd()
            toByteArray()
        }
}
