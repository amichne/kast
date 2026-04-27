package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.testing.AnalysisBackendContractAssertions
import io.github.amichne.kast.testing.AnalysisBackendContractFixture
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AnalysisBackendContractTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `standalone backend satisfies the shared contract fixture`(): TestResult = runTest {
        val fixture = AnalysisBackendContractFixture.create(workspaceRoot)
        val session = AnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = AnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            AnalysisBackendContractAssertions.assertCommonContract(
                backend = backend,
                fixture = fixture,
            )
        }
    }
}
