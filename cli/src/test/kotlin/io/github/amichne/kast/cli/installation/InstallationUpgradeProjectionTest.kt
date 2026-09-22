package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.InstalledUpgradeRejection
import io.github.amichne.kast.appserver.runtime.DaemonUpgradeFailure
import io.github.amichne.kast.appserver.runtime.UpgradeBlocker
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.kernel.NonEmptyFailures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InstallationUpgradeProjectionTest {
    @Test
    fun `pending update retains its blocker in the installer rejection document`() {
        val handling =
            InstallationCliInspection.project(
                InstallationOutcome.UpgradePending(NonEmptyFailures.one(UpgradeBlocker.ACTIVE_TURN))
            ) as InstallationHandling.Handled
        val exit = assertInstanceOf(CliExit.BoundaryRejected::class.java, handling.exit)
        val document = Json.parseToJsonElement(exit.document.value).jsonObject
        assertEquals(setOf("operation", "status", "reason", "blockers"), document.keys)
        assertEquals("installation.install", document.getValue("operation").jsonPrimitive.content)
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("prior-daemon-pending", document.getValue("reason").jsonPrimitive.content)
        assertEquals(listOf("ACTIVE_TURN"), document.getValue("blockers").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `daemon failure retains its finite cause in the installer rejection document`() {
        val handling =
            InstallationCliInspection.project(
                InstallationOutcome.UpgradeRejected(
                    InstalledUpgradeRejection.Daemon(
                        DaemonManagementRejection.Upgrade(DaemonUpgradeFailure.NOT_QUIESCENT)
                    )
                )
            ) as InstallationHandling.Handled
        val exit = assertInstanceOf(CliExit.BoundaryRejected::class.java, handling.exit)
        val document = Json.parseToJsonElement(exit.document.value).jsonObject
        assertEquals(setOf("operation", "status", "reason", "field", "limit"), document.keys)
        assertEquals("installation.install", document.getValue("operation").jsonPrimitive.content)
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("daemon-upgrade-not-quiescent", document.getValue("reason").jsonPrimitive.content)
        assertEquals(JsonNull, document.getValue("field"))
        assertEquals(JsonNull, document.getValue("limit"))
    }
}
