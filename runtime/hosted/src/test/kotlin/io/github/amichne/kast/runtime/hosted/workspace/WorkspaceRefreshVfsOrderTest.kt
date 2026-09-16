package io.github.amichne.kast.runtime.hosted.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class WorkspaceRefreshVfsOrderTest {
    @Test
    fun `explicit refresh dirties the admitted tree before scheduling native refresh`() {
        val calls = mutableListOf<String>()
        val resource = "/io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort.class"
        javaClass.getResourceAsStream(resource)!!.use { input ->
            ClassReader(input)
                .accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? =
                            if (name != "refreshFiles") null
                            else
                                object : MethodVisitor(Opcodes.ASM9) {
                                    override fun visitMethodInsn(
                                        opcode: Int,
                                        owner: String,
                                        name: String,
                                        descriptor: String,
                                        isInterface: Boolean,
                                    ) {
                                        if (owner == "com/intellij/openapi/vfs/VfsUtil" && name == "markDirty")
                                            calls += "dirty"
                                        if (
                                            owner == "com/intellij/openapi/vfs/newvfs/RefreshQueue" && name == "refresh"
                                        )
                                            calls += "refresh"
                                    }
                                }
                    },
                    ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
        }
        assertEquals(listOf("dirty", "refresh"), calls)
    }
}
