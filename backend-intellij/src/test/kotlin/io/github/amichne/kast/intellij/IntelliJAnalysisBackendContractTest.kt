package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.github.amichne.kast.testing.AnalysisBackendContractAssertions
import io.github.amichne.kast.testing.AnalysisBackendContractFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readText

class IntelliJAnalysisBackendContractTest : IntelliJFixtureTestCase() {
    @TempDir
    lateinit var tempHome: Path

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

    @Test
    fun `project activity starts a project scoped server and registers a descriptor`() = runBlocking {
        val startupProperty = "kast.enable.startup.activity.tests"
        val originalUserHome = System.getProperty("user.home")
        val originalStartupFlag = System.getProperty(startupProperty)
        val descriptorDirectory = tempHome.resolve(".kast/instances")
        val projectBasePath = checkNotNull(fixture.project.basePath)
        val service = fixture.project.getService(KastProjectService::class.java)
        var descriptorPath: Path? = null

        System.setProperty("user.home", tempHome.toString())
        System.setProperty(startupProperty, "true")
        try {
            KastProjectActivity().execute(fixture.project)

            descriptorPath = waitForCondition("server descriptor") {
                if (descriptorDirectory.toFile().isDirectory) {
                    descriptorDirectory.listDirectoryEntries("*.json").singleOrNull()
                } else {
                    null
                }
            }
            val descriptor = Json.decodeFromString<ServerInstanceDescriptor>(descriptorPath.readText())

            assertEquals("intellij", descriptor.backendName)
            assertEquals("0.1.0", descriptor.backendVersion)
            assertEquals(projectBasePath, descriptor.workspaceRoot)

            val healthResponse = waitForCondition("health response") {
                fetchHealth(descriptor)
            }
            assertEquals("ok", healthResponse.status)
            assertEquals(descriptor.workspaceRoot, healthResponse.workspaceRoot)
        } finally {
            service.dispose()
            descriptorPath?.let { path ->
                waitForCondition("descriptor cleanup") {
                    if (path.notExists()) true else null
                }
            }
            if (originalStartupFlag == null) {
                System.clearProperty(startupProperty)
            } else {
                System.setProperty(startupProperty, originalStartupFlag)
            }
            System.setProperty("user.home", originalUserHome)
        }
    }

    private fun createContractFixture(): AnalysisBackendContractFixture {
        return AnalysisBackendContractFixture.create(
            workspaceRoot = workspaceRoot(),
        ) { relativePath, content ->
            writeWorkspaceFile(relativePath, content)
        }
    }

    private fun fetchHealth(descriptor: ServerInstanceDescriptor): HealthResponse? {
        val response = runCatching {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                    URI.create("http://${descriptor.host}:${descriptor.port}/api/v1/health"),
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrNull() ?: return null

        if (response.statusCode() != 200) {
            return null
        }

        return Json.decodeFromString<HealthResponse>(response.body())
    }

    private fun <T : Any> waitForCondition(
        label: String,
        timeoutMillis: Long = 15_000,
        pollIntervalMillis: Long = 100,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(pollIntervalMillis)
        }

        val directoryContents = if (tempHome.toFile().exists()) {
            tempHome.toFile().walkTopDown().joinToString(separator = "\n") { it.relativeTo(tempHome.toFile()).path }
        } else {
            "<temp home missing>"
        }
        throw AssertionError(
            "Timed out waiting for $label under $tempHome. Current contents:\n$directoryContents",
        )
    }
}
