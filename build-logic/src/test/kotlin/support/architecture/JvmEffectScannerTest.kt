package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

class JvmEffectScannerTest {
    @Test
    fun `filesystem mutation is derived from compiled bytecode`(@TempDir temporary: Path) {
        val classFile = copyClassFile(FilesMutationFixture::class.java, temporary)

        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(module(ModuleId.INDEXER), listOf(classFile)),
        )

        assertTrue(
            scanned.effects().any { effect ->
                effect.effect == ForbiddenEffect.FILESYSTEM_WRITE &&
                effect.target.owner == JvmClassName("java/nio/file/Files") &&
                effect.target.name == JvmMemberName("deleteIfExists")
            },
        )
        (scanned.effects() as MutableSet).clear()
        assertTrue(scanned.effects().any { it.effect == ForbiddenEffect.FILESYSTEM_WRITE })
    }

    @Test
    fun `malformed class is a closed scan failure`(@TempDir temporary: Path) {
        val malformed = temporary.resolve("Malformed.class")
        Files.writeString(malformed, "not bytecode")

        val failed = assertInstanceOf<BytecodeScanOutcome.Failed>(
            JvmEffectScanner.scan(module(ModuleId.INDEXER), listOf(malformed)),
        )

        assertEquals(listOf(BytecodeScanFailure.MalformedClass(malformed)), failed.failures())
        (failed.failures() as MutableList).clear()
        assertEquals(listOf(BytecodeScanFailure.MalformedClass(malformed)), failed.failures())
    }

    @Test
    fun `truncated class is a closed scan failure`() {
        val failed = assertInstanceOf<BytecodeScanOutcome.Failed>(
            JvmEffectScanner.scanBytes(
                module(ModuleId.INDEXER),
                listOf(HostedReadClassBytes.capture("Truncated.class", byteArrayOf(0xCA.toByte()))),
            ),
        )

        assertEquals(
            listOf(BytecodeScanFailure.MalformedClass(Path.of("Truncated.class"))),
            failed.failures(),
        )
    }

    @Test
    fun `invalid class identity is a closed scan failure`() {
        val failed = assertInstanceOf<BytecodeScanOutcome.Failed>(
            JvmEffectScanner.scanBytes(
                module(ModuleId.INDEXER),
                listOf(HostedReadClassBytes.capture("Invalid\u0000.class", emptyClassBytes())),
            ),
        )

        assertEquals(
            listOf(BytecodeScanFailure.InvalidClassIdentity("Invalid\u0000.class")),
            failed.failures(),
        )
    }

    @Test
    fun `invalid descriptor is a closed scan failure`() {
        val invalidDescriptor = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/InvalidDescriptor", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PRIVATE, "broken", "invalid", null, null).visitEnd()
            visitEnd()
        }.toByteArray()

        val failed = assertInstanceOf<BytecodeScanOutcome.Failed>(
            JvmEffectScanner.scanBytes(
                module(ModuleId.INDEXER),
                listOf(HostedReadClassBytes.capture("InvalidDescriptor.class", invalidDescriptor)),
            ),
        )

        assertEquals(
            listOf(BytecodeScanFailure.MalformedClass(Path.of("InvalidDescriptor.class"))),
            failed.failures(),
        )
    }

    @Test
    fun `source mutation authority is derived from caller and target bytecode`(@TempDir temporary: Path) {
        val classFile = sourceMutationClassFile(
            temporary,
            "io/github/amichne/kast/api/io/LocalDiskFileOperations",
        )

        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(module(ModuleId.KERNEL), listOf(classFile)),
        )

        assertTrue(scanned.effects().any { it.effect == ForbiddenEffect.SOURCE_FILESYSTEM_WRITE })
    }

    @Test
    fun `mutation package naming alone does not manufacture source authority`(@TempDir temporary: Path) {
        val classFile = sourceMutationClassFile(temporary, "fixture/mutation/SourceWriter")

        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(module(ModuleId.KERNEL), listOf(classFile)),
        )

        assertTrue(scanned.effects().any { it.effect == ForbiddenEffect.FILESYSTEM_WRITE })
        assertTrue(scanned.effects().none { it.effect == ForbiddenEffect.SOURCE_FILESYSTEM_WRITE })
    }

    private fun copyClassFile(
        type: Class<*>,
        temporary: Path,
    ): Path {
        val resource = "/${type.name.replace('.', '/')}.class"
        val target = temporary.resolve("${type.simpleName}.class")
        type.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "missing fixture bytecode: $resource" }
            Files.copy(input, target)
        }
        return target
    }

    private fun module(id: ModuleId): ValidatedModulePolicy =
        (KastArchitecturePolicy.validate() as ArchitecturePolicyValidation.Valid)
            .architecture.modules.getValue(id)

    private fun sourceMutationClassFile(
        temporary: Path,
        owner: String,
    ): Path {
        val bytecode = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "delete",
                "(Ljava/nio/file/Path;)Z",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/nio/file/Files",
                    "deleteIfExists",
                    "(Ljava/nio/file/Path;)Z",
                    false,
                )
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        return temporary.resolve("SourceWriter.class").also { Files.write(it, bytecode) }
    }

    private fun emptyClassBytes(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Empty", null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    @Suppress("unused")
    private class FilesMutationFixture {
        fun delete(path: Path): Boolean = Files.deleteIfExists(path)
    }
}
