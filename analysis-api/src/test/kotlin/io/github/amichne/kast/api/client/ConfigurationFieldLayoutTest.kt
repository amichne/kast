package io.github.amichne.kast.api.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationFieldLayoutTest {
    @Test
    fun configurationFieldDeclarationsAreSplitIntoTheFieldsPackage() {
        val sourceRoot = sourceRoot()
        val clientDir = sourceRoot.resolve("io/github/amichne/kast/api/client")
        val fieldsDir = clientDir.resolve("fields")

        assertTrue(Files.isDirectory(fieldsDir), "Configuration field declarations should live in api.client.fields")
        assertTrue(
            Files.notExists(clientDir.resolve("ConfigurationField.kt")),
            "ConfigurationField.kt should move out of api.client"
        )

        val actualFiles = Files.list(fieldsDir).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .map { it.fileName.toString() }
                .filter { it.endsWith(".kt") }
                .toList()
                .toSet()
        }

        assertEquals(expectedFieldFiles, actualFiles)
        expectedFieldFiles.forEach { fileName ->
            val text = Files.readString(fieldsDir.resolve(fileName))
            assertTrue(
                text.startsWith("package io.github.amichne.kast.api.client.fields\n"),
                "$fileName should declare the api.client.fields package",
            )
        }
    }

    private fun sourceRoot(): Path {
        val moduleRoot = Path.of("src/main/kotlin")
        if (Files.isDirectory(moduleRoot)) return moduleRoot
        return Path.of("analysis-api/src/main/kotlin")
    }

    private companion object {
        val expectedFieldFiles = setOf(
            "CacheEnabled.kt",
            "CacheSourceIndexSaveDelayMillis.kt",
            "CacheWriteDelayMillis.kt",
            "CliBinaryPath.kt",
            "ConfigurationDefault.kt",
            "ConfigurationDefaults.kt",
            "ConfigurationField.kt",
            "GradleToolingApiTimeoutMillis.kt",
            "IndexingIdentifierIndexWaitMillis.kt",
            "IndexingPhase2BatchSize.kt",
            "IndexingPhase2Enabled.kt",
            "IndexingPhase2Parallelism.kt",
            "IndexingPhase2PriorityDepth.kt",
            "IndexingReferenceBatchSize.kt",
            "IndexingRemoteEnabled.kt",
            "IndexingRemoteSourceIndexUrl.kt",
            "IdeaBackendEnabled.kt",
            "IdeaLaunchCommand.kt",
            "IdeaLaunchEnabled.kt",
            "IdeaLaunchRequireInstalledPlugin.kt",
            "IdeaLaunchWaitTimeoutMillis.kt",
            "OptionalConfigString.kt",
            "PathsBinDir.kt",
            "PathsCacheDir.kt",
            "PathsDescriptorDir.kt",
            "PathsInstallRoot.kt",
            "PathsLibDir.kt",
            "PathsLogsDir.kt",
            "PathsSocketDir.kt",
            "ProfilingDurationSeconds.kt",
            "ProfilingEnabled.kt",
            "ProfilingEmitManifest.kt",
            "ProfilingModes.kt",
            "ProfilingOtlpEndpoint.kt",
            "ProfilingOutputDir.kt",
            "ProjectOpenAutoExcludeGit.kt",
            "ProjectOpenProfile.kt",
            "ProjectOpenProfileAutoInit.kt",
            "ServerMaxConcurrentRequests.kt",
            "ServerMaxResults.kt",
            "ServerRequestTimeoutMillis.kt",
            "RuntimeDefaultBackend.kt",
            "HeadlessBackendEnabled.kt",
            "HeadlessIdeaHome.kt",
            "HeadlessRuntimeLibsDir.kt",
            "TelemetryDetail.kt",
            "TelemetryEnabled.kt",
            "TelemetryOutputFile.kt",
            "TelemetryScopes.kt",
            "WatcherDebounceMillis.kt",
        )
    }
}
