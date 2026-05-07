package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.StandaloneServerOptions
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.contract.AnalysisTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StandaloneRuntimeConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `standalone server writes descriptors to configured descriptor directory`() {
        val descriptorDirectory = tempDir.resolve("workspace-config").resolve("daemons")
        val config = KastConfig.defaults().copy(
            paths = KastConfig.defaults().paths.copy(
                descriptorDir = PathsDescriptorDir(descriptorDirectory.toString()),
            ),
        )
        val options = StandaloneServerOptions(
            workspaceRoot = tempDir.resolve("workspace"),
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            transport = AnalysisTransport.Stdio,
            requestTimeoutMillis = 1_000L,
            maxResults = 10,
            maxConcurrentRequests = 1,
        )

        val serverConfig = standaloneAnalysisServerConfig(
            options = options,
            config = config,
            workspaceFileCount = 7,
        )

        assertEquals(descriptorDirectory.toAbsolutePath().normalize(), serverConfig.descriptorDirectory)
        assertEquals(7, serverConfig.workspaceFileCount)
    }
}
