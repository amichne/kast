package io.github.amichne.kast.distribution.contract.gradle

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GradleImportEnvironmentTest {
    @Test
    fun `only explicit names are admitted and values never appear in evidence`() {
        val admitted = admitted("PROJECT_TOKEN", "", mapOf("PROJECT_TOKEN" to "classified-token", "SECRET_OTHER" to "unselected"))
        assertEquals(mapOf("PROJECT_TOKEN" to "classified-token"), admitted.processVariables())
        assertEquals(listOf("PROJECT_TOKEN"), admitted.evidence.map { it.name.value })
        assertFalse(admitted.toString().contains("classified-token"))
        assertFalse(admitted.evidence.toString().contains("classified-token"))
        assertFalse(admitted.toString().contains("unselected"))
    }

    @Test
    fun `reserved launch inputs cannot be selected`() {
        for (name in listOf("JAVA_HOME", "HOME", "PATH", "KAST_RUNTIME_ID", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "DYLD_INSERT_LIBRARIES", "BASH_ENV")) {
            assertEquals(Refinement.Rejected(GradleImportEnvironmentFailure.RESERVED_VARIABLE),
                GradleImportEnvironment.admit(name, "", mapOf(name to "anything")))
        }
    }

    @Test
    fun `missing and malformed explicit inputs fail closed`() {
        assertEquals(Refinement.Rejected(GradleImportEnvironmentFailure.MISSING_VARIABLE),
            GradleImportEnvironment.admit("REQUIRED", "", emptyMap()))
        assertEquals(Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_VARIABLE_NAME),
            GradleImportEnvironment.admit("=bad", "", emptyMap()))
        for (path in listOf("relative", "/tmp/../bin", "/tmp:", "/tmp\n/bin")) {
            assertEquals(Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_EXECUTABLE_PATH),
                GradleImportEnvironment.admit("", path, emptyMap()))
        }
    }

    @Test
    fun `identity changes only with selected inputs and executable order`() {
        val original = admitted("A,B", "/opt/tools:/opt/sdk", mapOf("A" to "one", "B" to "two", "SECRET" to "first"))
        val reordered = admitted("B,A,A", "/opt/tools:/opt/sdk", mapOf("A" to "one", "B" to "two", "SECRET" to "second"))
        assertEquals(original.identity, reordered.identity)
        assertNotEquals(original.identity, admitted("A,B", "/opt/tools:/opt/sdk", mapOf("A" to "changed", "B" to "two")).identity)
        assertNotEquals(original.identity, admitted("A,B", "/opt/sdk:/opt/tools", mapOf("A" to "one", "B" to "two")).identity)
        assertNotEquals(original.identity, admitted("A", "/opt/tools:/opt/sdk", mapOf("A" to "one")).identity)
    }

    private fun admitted(names: String, path: String, ambient: Map<String, String>): GradleImportEnvironment =
        (GradleImportEnvironment.admit(names, path, ambient) as Refinement.Refined).value
}
