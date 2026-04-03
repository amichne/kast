package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CliJsonTest {
    @Test
    fun `writeCliJson serializes install results`() {
        val output = StringBuilder()

        writeCliJson(
            output = output,
            value = InstallResult(
                instanceName = "my-dev",
                instanceRoot = "/tmp/instances/my-dev",
                launcherPath = "/tmp/bin/kast-my-dev",
            ),
            json = defaultCliJson(),
        )

        val result = defaultCliJson().decodeFromString<InstallResult>(output.toString())

        assertEquals("my-dev", result.instanceName)
        assertEquals("/tmp/instances/my-dev", result.instanceRoot)
        assertEquals("/tmp/bin/kast-my-dev", result.launcherPath)
    }
}
