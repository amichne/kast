package io.github.amichne.kast.appserver.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

class InvocationFenceTest {
    @Test fun `restart fences a started effect as uncertain`(@TempDir root: Path) {
        val file=root.toRealPath().resolve("invocations.json"); val fingerprint=InvocationFence.digest("arguments")
        assertEquals(InvocationAdmission.Admitted,InvocationFence(file).admit("call",fingerprint))
        assertEquals(InvocationAdmission.Rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),InvocationFence(file).admit("call",fingerprint))
    }
    @Test fun `completed effects cannot execute again and input conflict stays distinct`(@TempDir root: Path) {
        val file=root.toRealPath().resolve("invocations.json"); val fingerprint=InvocationFence.digest("arguments")
        val fence=InvocationFence(file)
        fence.admit("call",fingerprint); fence.finish("call",InvocationPhase.COMPLETED)
        assertEquals(InvocationAdmission.Rejected(InvocationFenceFailure.ALREADY_COMPLETED),InvocationFence(file).admit("call",fingerprint))
        assertEquals(InvocationAdmission.Rejected(InvocationFenceFailure.INPUT_CONFLICT),InvocationFence(file).admit("call",InvocationFence.digest("changed")))
    }
    @Test fun `corrupt journal never permits execution`(@TempDir root: Path) {
        val file=root.toRealPath().resolve("invocations.json"); Files.writeString(file,"broken")
        assertEquals(InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED),InvocationFence(file).admit("call",InvocationFence.digest("x")))
    }
}
