package io.github.amichne.kast.appserver.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InvocationFenceTest {
    @Test
    fun `completed settlement cannot be downgraded to uncertain`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fingerprint = InvocationFence.digest("arguments")
        val fence = InvocationFence(file)
        assertEquals(InvocationAdmission.Admitted, fence.admit("call", fingerprint))
        assertEquals(InvocationAdmission.Admitted, fence.finish("call", InvocationSettlement.COMPLETED))
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.TRANSITION_REJECTED),
            fence.finish("call", InvocationSettlement.UNCERTAIN),
        )
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.ALREADY_COMPLETED),
            InvocationFence(file).admit("call", fingerprint),
        )
    }

    @Test
    fun `restart fences a started effect as uncertain`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fingerprint = InvocationFence.digest("arguments")
        assertEquals(InvocationAdmission.Admitted, InvocationFence(file).admit("call", fingerprint))
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),
            InvocationFence(file).admit("call", fingerprint),
        )
    }

    @Test
    fun `completed effects cannot execute again and input conflict stays distinct`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fingerprint = InvocationFence.digest("arguments")
        val fence = InvocationFence(file)
        fence.admit("call", fingerprint)
        fence.finish("call", InvocationSettlement.COMPLETED)
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.ALREADY_COMPLETED),
            InvocationFence(file).admit("call", fingerprint),
        )
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.INPUT_CONFLICT),
            InvocationFence(file).admit("call", InvocationFence.digest("changed")),
        )
    }

    @Test
    fun `corrupt journal never permits execution`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        Files.writeString(file, "broken")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.DOCUMENT_MALFORMED),
            InvocationFence(file).admit("call", InvocationFence.digest("x")),
        )
        assertEquals("broken", Files.readString(file))
    }

    @Test
    fun `non-private corrupt journal is rejected before parsing`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        Files.writeString(file, "broken")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED),
            InvocationFence(file).admit("call", InvocationFence.digest("x")),
        )
        assertEquals("broken", Files.readString(file))
    }
}
