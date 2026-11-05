package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.selectedBrokerJbr
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Native receipt parsing must preserve ownership rejection before any launchd effect. */
@Tag("public-launcher")
class NativePublishedReceiptTest {
    @Test
    fun `native disable reads a prior receipt and preserves an unowned login entry`(@TempDir evidence: Path) {
        NativePublicFixture(evidence).use { fixture ->
            val selected = selectedBrokerJbr(fixture.home)
            Files.writeString(
                fixture.installation.resolve("config/environment"),
                "${selected.first}=${selected.second}\n",
            )
            val kast = Files.writeString(fixture.installation.resolve("bin/kast"), "#!/bin/sh\nexit 99\n")
            assertTrue(kast.toFile().setExecutable(true))
            val codex = Files.createDirectory(fixture.home.resolve(".codex"))
            val profile = nativeDigestText(codex.toString()).take(16)
            val receipt = fixture.installation.resolve("state/broker/$profile/service.plist")
            Files.createDirectories(receipt.parent)
            val priorReceipt = "<plist><dict><key>Label</key><string>another-owner</string></dict></plist>"
            Files.writeString(receipt, priorReceipt)
            val label = "io.github.amichne.kast.broker.${nativeDigestText(fixture.installation.toString()).take(32)}"
            val login = fixture.home.resolve("Library/LaunchAgents/$label.login.plist")
            Files.createDirectories(login.parent)
            Files.writeString(login, "unowned login entry\n")

            val child =
                ProcessBuilder(
                        fixture.payload.toString(),
                        "-Duser.home=${fixture.home}",
                        "--internal-entrypoint=service",
                        "disable",
                    )
                    .directory(fixture.home.toFile())
                    .redirectOutput(evidence.resolve("disable.stdout").toFile())
                    .redirectError(evidence.resolve("disable.stderr").toFile())
                    .apply {
                        environment().clear()
                        environment().putAll(mapOf("HOME" to fixture.home.toString(), "PATH" to "/usr/bin:/bin"))
                    }
                    .start()
            child.outputStream.close()
            try {
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Native receipt admission did not settle")
                val error = Files.readString(evidence.resolve("disable.stderr"))
                assertEquals(64, child.exitValue(), error)
                val rejected = Json.parseToJsonElement(error.trim()).jsonObject
                assertEquals("management", rejected.getValue("type").jsonPrimitive.content)
                assertEquals("SERVICE_OWNERSHIP_UNPROVEN", rejected.getValue("failure").jsonPrimitive.content)
                assertEquals(priorReceipt, Files.readString(receipt))
                assertEquals("unowned login entry\n", Files.readString(login))
            } finally {
                if (child.isAlive) child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            }
        }
    }
}

private fun nativeDigestText(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
