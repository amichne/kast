package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkspaceRefreshCommandAdmissionTest {
    @Test
    fun `duplicate outer and nested command fields reject before typed decoding`() {
        val commands =
            listOf(
                WorkspaceRefreshCommand.Request("refresh", WorkspaceRefreshEffect.FILE_REFRESH),
                WorkspaceRefreshCommand.Configure(
                    WorkspaceRefreshRule.TaskSuccess(":build", WorkspaceRefreshEffect.FILE_REFRESH)
                ),
            )
        for (command in commands) {
            val encoded = Json.encodeToString<WorkspaceRefreshCommand>(command)
            assertEquals(command, decodeWorkspaceRefreshCommand(encoded))
            val ambiguous = encoded.replace("\"effect\":", "\"effect\":\"GRADLE_MODEL_RELOAD\",\"effect\":")
            assertThrows<IllegalArgumentException> { decodeWorkspaceRefreshCommand(ambiguous) }
        }
    }
}
