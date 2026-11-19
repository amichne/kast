package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceDependencySharingTest {
    @Test
    fun `sharing is the default and only the closed settings are admitted`() {
        val key = "KAST_WORKSPACE_DEPENDENCY_SHARING"
        val defaults = ResolvedKastConfiguration.resolve(ConfigurationSources()) as Refinement.Refined
        assertEquals(WorkspaceDependencySharing.SHARED, defaults.value.workspaceDependencySharing)
        for ((value, expected) in
            listOf("PRIVATE" to WorkspaceDependencySharing.PRIVATE, "SHARED" to WorkspaceDependencySharing.SHARED)) {
            val resolved =
                ResolvedKastConfiguration.resolve(ConfigurationSources(savedWorkspace = listOf(key to value)))
                    as Refinement.Refined
            assertEquals(expected, resolved.value.workspaceDependencySharing)
        }
        val declaration = ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING.declaration()
        assertEquals(ConfigurationBoundary.NEXT_WORKSPACE_CREATION, declaration.applicationBoundary)
        assertEquals(listOf("PRIVATE", "SHARED"), declaration.admittedRange.finiteValues)
        for (value in listOf("", "shared", " SHARED", "AUTO", "1")) {
            val rejected =
                ResolvedKastConfiguration.resolve(ConfigurationSources(environment = mapOf(key to value)))
                    as Refinement.Rejected
            assertEquals(ConfigurationFailure.INVALID_VALUE, rejected.failure.reason)
            assertEquals(key, rejected.failure.key)
        }
    }
}
