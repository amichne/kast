package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceRefreshTriggerPolicyTest {
    @Test
    fun `only one exact task in the opted in rule selects an effect`() {
        val policy = WorkspaceRefreshTriggerPolicy()
        assertNull(policy.select(listOf(":compileKotlin")))
        val rule = WorkspaceRefreshRule.TaskSuccess(":compileKotlin", WorkspaceRefreshEffect.FILE_REFRESH)
        assertTrue(policy.configure(rule) is WorkspaceRefreshResult.Configured)
        assertEquals(rule, policy.select(listOf(":compileKotlin")))
        assertEquals(WorkspaceRefreshEffect.FILE_REFRESH, policy.success(rule))
        assertNull(policy.select(listOf("compileKotlin")))
        assertNull(policy.select(listOf(":test", ":compileKotlin")))
        assertNull(policy.select(listOf(":other:compileKotlin")))
        policy.configure(WorkspaceRefreshRule.Off)
        assertNull(policy.success(rule))
    }

    @Test
    fun `invalid rule does not replace admitted rule and old events cannot use a new rule`() {
        val policy = WorkspaceRefreshTriggerPolicy()
        val original = WorkspaceRefreshRule.TaskSuccess(":build", WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        policy.configure(original)
        assertTrue(
            policy.configure(WorkspaceRefreshRule.TaskSuccess("*", WorkspaceRefreshEffect.FILE_REFRESH))
                is WorkspaceRefreshResult.Rejected
        )
        assertEquals(original, policy.select(listOf(":build")))
        policy.configure(original.copy())
        assertNull(policy.success(original))
    }
}
