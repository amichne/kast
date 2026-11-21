package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** The Control executable replaces itself with one JVM entry; only its client entries publish one-shot records. */
class ControlEntryLifecycleTest {
    private val installation = Path.of("/installations/kast/installation")
    private val record = InstalledToolInvocation(1, 41, 1_000)

    private fun admit(vararg entry: String, classPath: String = "$installation/lib/cli.jar") =
        OwnedLifecycleProcess.admit(
            record,
            LifecycleProcessObservation.Present(
                LifecycleProcessSnapshot(
                    41,
                    1_000,
                    "/runtimes/abc/host/jbr/bin/java",
                    listOf("-Duser.home=/home/caller", "-cp", classPath, "io.github.amichne.kast.cli.KastControlMain") +
                        entry,
                )
            ),
            installation,
        )

    @Test
    fun `client entries of the single Control main class are owned`() {
        for (entry in
            listOf(
                listOf("--internal-entrypoint=mcp"),
                listOf("--internal-entrypoint=mcp"),
                listOf("--internal-entrypoint=query", "query"),
                listOf("query", "--name", "Fixture"),
            )) {
            assertInstanceOf(ShutdownProcessAdmission.Owned::class.java, admit(*entry.toTypedArray()), entry.toString())
        }
    }

    @Test
    fun `service, daemon, installer and management entries are not one-shot owners`() {
        for (entry in
            listOf(
                listOf("--internal-entrypoint=service", "semantic-ensure"),
                listOf("--internal-entrypoint=daemon", "--semantic"),
                listOf("--internal-entrypoint=installer"),
                listOf("install"),
            )) {
            assertEquals(ShutdownProcessAdmission.Rejected, admit(*entry.toTypedArray()), entry.toString())
        }
    }

    @Test
    fun `a class path outside the installation is not owned`() {
        assertEquals(
            ShutdownProcessAdmission.Rejected,
            admit("--internal-entrypoint=mcp", classPath = "/elsewhere/lib/cli.jar"),
        )
    }
}
