package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
    fun `activation policy preserves the sole persistent installation`(@TempDir temporary: Path) {
        for (policy in InstallationActivationPolicy.entries) {
            val root = Files.createDirectory(temporary.resolve(policy.name)).toRealPath()
            val installation = root.resolve("home/.local/share/kast")
            val home = Files.createDirectory(root.resolve("home"))
            val request =
                releaseRequest(
                    root,
                    installation,
                    root.resolve("home/.local/bin"),
                    home,
                    Files.createDirectory(root.resolve("codex-home")),
                    "1.2.3",
                    environmentOverrides =
                        mapOf(
                            "KAST_INSTALL_PROFILE" to "persistent",
                            "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private",
                        ),
                )
            val service = request.controlRoot.value.resolve("share/kast/libexec/kast-service")
            Files.createDirectories(service.parent)
            Files.writeString(service, "#!/bin/sh\nexit 0\n")
            service.toFile().setExecutable(true)
            val result = executeFixtureInstallation(request, policy)
            val report = assertInstanceOf(InstallationOutcome.Complete::class.java, result).report
            assertEquals(InstallationReportStatus.INSTALLED, report.status)
            assertEquals(InstallationActivation.NotRequested, report.activation)
            assertTrue(
                Files.readString(installation.resolve("installation/config/environment"))
                    .contains("KAST_APP_SERVER_PUBLIC_ENDPOINT=private")
            )
            assertLoginAnchor(installation.resolve("installation").toRealPath(), home)
        }
    }

    private fun assertLoginAnchor(installed: Path, home: Path) {
        val manifest = Json.parseToJsonElement(Files.readString(installed.resolve("installation.json"))).jsonObject
        val login =
            manifest
                .getValue("externalAnchors")
                .jsonArray
                .single {
                    it.jsonObject.getValue("kind").jsonPrimitive.content == "login"
                }
                .jsonObject
        val label = login.getValue("expectedLabel").jsonPrimitive.content
        val installationHash =
            MessageDigest.getInstance("SHA-256")
                .digest(installed.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
        assertEquals("io.github.amichne.kast.broker.$installationHash", label)
        assertEquals(
            home.resolve("Library/LaunchAgents/$label.login.plist").toString(),
            login.getValue("path").jsonPrimitive.content,
        )
        assertEquals(
            installed.resolve("share/kast/libexec/kast-daemon").toString(),
            login.getValue("expectedExecutable").jsonPrimitive.content,
        )
    }

    @Test
    fun `plan excludes broker activation from the reported changes`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val request =
            releaseRequest(
                root,
                home.resolve(".local/share/kast"),
                home.resolve(".local/bin"),
                home,
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
                mode = InstallationMode.PLAN,
            )
        val outcome = assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(request))
        assertEquals(InstallationActivation.Planned, outcome.report.activation)
        assertFalse("enable-app-server" in outcome.report.changes)
        assertTrue(Files.notExists(request.installRoot.value))
    }
}
