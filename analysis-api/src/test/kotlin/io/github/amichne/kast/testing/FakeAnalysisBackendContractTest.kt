package io.github.amichne.kast.testing

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FakeAnalysisBackendContractTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `fake backend satisfies the shared contract fixture`(): TestResult = runTest {
        val fixture = AnalysisBackendContractFixture.create(workspaceRoot)
        val backend = FakeAnalysisBackend.contractFixture(fixture)

        AnalysisBackendContractAssertions.assertCommonContract(
            backend = backend,
            fixture = fixture,
        )
    }
}
