package io.github.amichne.kast.runtime.hosted.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class WorkspaceRefreshVfsOrderTest {
    @Test
    fun `semantic dispatch and lifecycle opening share the awaited read refresh`() {
        val callers = mutableSetOf<String>()
        val resource = "/io/github/amichne/kast/runtime/hosted/HostedEndpointService.class"
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
                        ): MethodVisitor? {
                            if (!name.startsWith("dispatch-") && !name.startsWith("lifecycleVfsRefresh-")) return null
                            val callerMethod = name
                            return object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String,
                                    name: String,
                                    descriptor: String,
                                    isInterface: Boolean,
                                ) {
                                    if (
                                        owner == "io/github/amichne/kast/runtime/hosted/HostedVfsRefreshOutcomeKt" &&
                                            name.startsWith("awaitHostedVfsRefresh-")
                                    ) {
                                        val caller =
                                            if (callerMethod.startsWith("lifecycleVfsRefresh-")) "lifecycle"
                                            else "dispatch"
                                        callers += caller
                                    }
                                }
                            }
                        }
                    },
                    ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
        }
        assertEquals(setOf("dispatch", "lifecycle"), callers)
    }

    @Test
    fun `forced refresh telemetry records finite success and failure outcomes`() {
        assertEquals(
            "kast_vfs_refresh policy=FORCED_RECONCILIATION outcome=SUCCEEDED",
            WorkspaceRefreshEffectResult.SUCCEEDED.refreshObservation(),
        )
        assertEquals(
            "kast_vfs_refresh policy=FORCED_RECONCILIATION outcome=FAILED",
            WorkspaceRefreshEffectResult.FAILED.refreshObservation(),
        )
    }

    @Test
    fun `automatic read refresh uses native recursive refresh without forced dirty marking`() {
        val trace = vfsCalls("refreshForRead")
        assertEquals(listOf("refresh"), trace.calls)
        assertEquals(listOf(Opcodes.ICONST_1, Opcodes.ICONST_1), trace.refreshFlags)
    }

    @Test
    fun `explicit refresh dirties the admitted tree before scheduling native refresh`() {
        val trace = vfsCalls("refreshFiles")
        assertEquals(listOf("dirty", "refresh"), trace.calls)
        assertEquals(listOf(Opcodes.ICONST_1, Opcodes.ICONST_1), trace.refreshFlags)
    }

    private data class VfsCallTrace(val calls: List<String>, val refreshFlags: List<Int>)

    private fun vfsCalls(method: String): VfsCallTrace {
        val calls = mutableListOf<String>()
        val refreshFlags = mutableListOf<Int>()
        var refreshQueueSelected = false
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
                            if (name != method) null
                            else
                                object : MethodVisitor(Opcodes.ASM9) {
                                    override fun visitInsn(opcode: Int) {
                                        if (refreshQueueSelected && refreshFlags.size < 2) refreshFlags += opcode
                                    }

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
                                            owner == "com/intellij/openapi/vfs/newvfs/RefreshQueue\$Companion" &&
                                                name == "getInstance"
                                        )
                                            refreshQueueSelected = true
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
        return VfsCallTrace(calls, refreshFlags)
    }
}
