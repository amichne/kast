package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IdeLifecycleOpenReplayTest {
    @Test
    fun `remaining allowance does not change retained open identity or extend its operation`() {
        val state = IdeLifecycleState(UUID.randomUUID())
        val client = LifecycleClient("client")
        val request = LifecycleRequest("open")
        val original = IdeLifecycleCommand.Open(state.host.toString(), request.value, client.value, "/workspace", 1000)
        val first = assertInstanceOf(LifecycleSubmission.Start::class.java, state.begin(original, request, client))
        assertEquals(
            LifecycleSubmission.Existing(first.pending),
            state.begin(original.copy(preparationMillis = 800), request, client),
        )
        val terminal = IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        state.complete(request, terminal)
        assertEquals(
            LifecycleSubmission.Existing(terminal),
            state.begin(original.copy(preparationMillis = 10_000), request, client),
        )
        for (changed in listOf(original.copy(root = "/other"), original.copy(client = "foreign"))) {
            assertEquals(
                LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.REQUEST_CONFLICT)),
                state.begin(changed, request, client),
            )
        }
    }
}
