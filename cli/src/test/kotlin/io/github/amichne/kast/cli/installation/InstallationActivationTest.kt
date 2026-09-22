package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationActivationTest {
    @Test
    fun `successful activation and opt out have distinct installed outcomes`(@TempDir temporary: Path) {
        for (refresh in listOf("0", "1")) {
            val root = Files.createDirectory(temporary.resolve(refresh)).toRealPath()
            val installation = root.resolve("installation")
            val result =
                InstallationWorkflow.execute(
                    releaseRequest(
                        root,
                        installation,
                        root.resolve("commands"),
                        Files.createDirectory(root.resolve("home")),
                        Files.createDirectory(root.resolve("codex-home")),
                        "1.2.3",
                        environmentOverrides =
                            mapOf(
                                "KAST_INSTALL_REFRESH_APP_SERVER" to refresh,
                                "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control",
                            ),
                    )
                )
            val report = assertInstanceOf(InstallationOutcome.Complete::class.java, result).report
            assertEquals(InstallationReportStatus.INSTALLED, report.status)
            assertEquals(
                if (refresh == "1") InstallationActivation.Ready else InstallationActivation.NotRequested,
                report.activation,
            )
            assertTrue(
                Files.readString(installation.resolve("current/config/environment"))
                    .contains("KAST_APP_SERVER_PUBLIC_ENDPOINT=codex-control")
            )
        }
    }

    @Test
    fun `activation rejection retains committed installation and can resume through its installed command`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val install = root.resolve("installation")
        val request =
            releaseRequest(
                root,
                install,
                root.resolve("commands"),
                home,
                Files.createDirectory(root.resolve("codex-home")),
                "1.2.3",
                environmentOverrides = mapOf("KAST_ENABLE_APP_SERVER" to "1", "KAST_INSTALL_REFRESH_APP_SERVER" to "1"),
            )
        Files.writeString(
            request.controlRoot.value.resolve("bin/kast"),
            """
            #!/bin/sh
            if [ "${'$'}1" = app-server ] && [ "${'$'}2" = enable ]; then
              test -f "${'$'}HOME/activation-ready"
              exit ${'$'}?
            fi
            exit 0
            """
                .trimIndent() + "\n",
        )
        val result = InstallationWorkflow.execute(request)
        val complete = assertInstanceOf(InstallationOutcome.Complete::class.java, result)
        verifyPendingReport(complete.report)
        val selected = install.resolve("current").toRealPath()
        assertTrue(Files.exists(selected.resolve("installation.json")))
        assertTrue(Files.readString(selected.resolve("config/environment")).contains("KAST_ENABLE_APP_SERVER=1"))
        val configuration = Files.readString(selected.resolve("config/environment"))
        assertTrue(configuration.contains("KAST_APP_SERVER_PUBLIC_ENDPOINT=private"))
        val identity = Files.readString(selected.resolve("installation.json"))
        Files.createFile(home.resolve("activation-ready"))
        val resume =
            ProcessBuilder(root.resolve("commands/kast").toString(), "app-server", "enable")
                .apply { environment()["HOME"] = home.toString() }
                .start()
        assertEquals(0, resume.waitFor())
        assertEquals(selected, install.resolve("current").toRealPath())
        assertEquals(configuration, Files.readString(selected.resolve("config/environment")))
        assertEquals(identity, Files.readString(selected.resolve("installation.json")))
    }

    private fun verifyPendingReport(report: InstallationReport) {
        assertEquals(InstallationReportStatus.INSTALLED_ACTIVATION_PENDING, report.status)
        assertEquals(
            InstallationActivation.Pending(InstallationActivationFailure.EXIT_REJECTED),
            report.activation,
        )
        assertFalse("enable-app-server" in report.changes)
        val encoded =
            Json.parseToJsonElement(
                    CanonicalJsonDocument.generated(InstallationReport.serializer()).create(report).value
                )
                .jsonObject
        assertEquals("installation.install", encoded.getValue("operation").jsonPrimitive.content)
        assertEquals("installed-activation-pending", encoded.getValue("status").jsonPrimitive.content)
        assertEquals("pending", encoded.getValue("activation").jsonObject.getValue("type").jsonPrimitive.content)
    }
}
