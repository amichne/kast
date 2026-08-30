package io.github.amichne.kast.runtime.ide.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class HostedRuntimeColdStartInitializationTest {
    @Test
    fun `runtime composition opens one aggregate mutation authority without another journal`() {
        val calls = mutableListOf<JvmCall>()
        val resource = "io/github/amichne/kast/runtime/ide/host/HostedIdeRuntimeComposition.class"
        val bytecode = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            resource
        }.use { it.readAllBytes() }

        ClassReader(bytecode).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (name.startsWith("open")) calls += JvmCall(owner, name)
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )

        assertEquals(
            1,
            calls.count { it.owner.startsWith(DURABLE_AUTHORITY_OWNER) },
            calls.toString(),
        )
        assertEquals(
            0,
            calls.count { it.owner.startsWith(RECOVERY_JOURNAL_OWNER) },
            calls.toString(),
        )
    }

    private data class JvmCall(
        val owner: String,
        val name: String,
    )

    private companion object {
        const val DURABLE_AUTHORITY_OWNER =
            "io/github/amichne/kast/evidence/sqlite/SqliteDurableChangeAuthority"
        const val RECOVERY_JOURNAL_OWNER =
            "io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal"
    }
}
