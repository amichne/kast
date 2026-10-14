package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PassiveRuntimeSocketTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `absent saved endpoint uses the shared default and recorded Codex home`() {
        assertEquals(
            Path.of("/selected/codex/app-server-control/app-server-control.sock"),
            selected(runtimeSocket(temporary, "/selected/codex")),
        )
    }

    @Test
    fun `explicit private endpoint uses only its physical socket or admitted alias`() {
        val installation = temporary.toRealPath().resolve("long-installation-name".repeat(6))
        Files.createDirectories(installation.resolve("config"))
        Files.writeString(installation.resolve("config/environment"), "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n")
        val directory = Files.createDirectories(installation.resolve("state/run"))
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(directory.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
        val alias = Path.of("/tmp/kast-uds-$hash")
        assertEquals(
            RuntimeObservationFailure.SOCKET_UNAVAILABLE,
            rejected(runtimeSocket(installation, "/selected/codex")),
        )
        Files.createSymbolicLink(alias, directory)
        try {
            assertEquals(alias.resolve("c.sock"), selected(runtimeSocket(installation, "/selected/codex")))
        } finally {
            Files.delete(alias)
        }
    }

    @Test
    fun `invalid and duplicate endpoint settings reject instead of choosing another socket`() {
        val configuration = Files.createDirectories(temporary.resolve("config")).resolve("environment")
        for (raw in
            listOf(
                "KAST_APP_SERVER_PUBLIC_ENDPOINT=unknown\n",
                "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\nKAST_APP_SERVER_PUBLIC_ENDPOINT=codex-control\n",
                "not-an-assignment\n",
            )) {
            Files.writeString(configuration, raw)
            assertEquals(
                RuntimeObservationFailure.CONFIGURATION_REJECTED,
                rejected(runtimeSocket(temporary, "/selected/codex")),
            )
        }
        Files.delete(configuration)
        Files.createSymbolicLink(configuration, temporary.resolve("missing"))
        assertEquals(
            RuntimeObservationFailure.CONFIGURATION_UNAVAILABLE,
            rejected(runtimeSocket(temporary, "/selected/codex")),
        )
    }

    @Test
    fun `relative or noncanonical recorded homes reject before socket selection`() {
        for (home in listOf("relative", "/selected/../foreign")) {
            assertEquals(RuntimeObservationFailure.MANIFEST_REJECTED, rejected(runtimeSocket(temporary, home)))
        }
    }

    private fun selected(result: Refinement<Path, RuntimeObservationFailure>): Path =
        (result as Refinement.Refined).value

    private fun rejected(result: Refinement<Path, RuntimeObservationFailure>): RuntimeObservationFailure =
        (result as Refinement.Rejected).failure
}
