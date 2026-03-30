package io.github.amichne.kast.intellij

import io.github.amichne.kast.testing.AnalysisBackendContractAssertions
import io.github.amichne.kast.testing.AnalysisBackendContractFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IntelliJAnalysisBackendContractTest : IntelliJFixtureTestCase() {
    @Test
    fun `intellij backend satisfies the shared contract fixture`() = runBlocking {
        val fixtureProject = createContractFixture()
        val backend = createBackend()

        AnalysisBackendContractAssertions.assertCommonContract(
            backend = backend,
            fixture = fixtureProject,
        )
    }

    @Test
    fun `intellij diagnostics report fixture syntax errors`() = runBlocking {
        val fixtureProject = createContractFixture()
        val backend = createBackend()

        AnalysisBackendContractAssertions.assertDiagnostics(
            backend = backend,
            fixture = fixtureProject,
        )
    }

    private fun createContractFixture(): AnalysisBackendContractFixture {
        return AnalysisBackendContractFixture.create(
            workspaceRoot = workspaceRoot(),
        ) { relativePath, content ->
            writeWorkspaceFile(relativePath, content)
        }
    }
}
