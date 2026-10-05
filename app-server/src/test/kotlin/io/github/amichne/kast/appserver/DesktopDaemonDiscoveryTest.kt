package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopDaemonDiscoveryTest {
    @Test
    fun `absent flag is owned before publication and removed on disable`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script =
            Script(
                Read(DesktopDaemonSetting.ABSENT),
                Enable,
                Read(DesktopDaemonSetting.ENABLED),
                Read(DesktopDaemonSetting.ENABLED),
                Remove,
                Read(DesktopDaemonSetting.ABSENT),
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        val record = record(target)
        val document = Json.parseToJsonElement(Files.readString(record)).jsonObject
        assertEquals(setOf("serviceIdentity", "previous", "type"), document.keys)
        assertEquals(target.identity.value, document.getValue("serviceIdentity").jsonPrimitive.content)
        assertEquals("ABSENT", document.getValue("previous").jsonPrimitive.content)
        assertEquals("DESKTOP_DAEMON_DISCOVERY", document.getValue("type").jsonPrimitive.content)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(record))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        assertFalse(Files.exists(record))
        script.exhausted()
    }

    @Test
    fun `preexisting flag is preserved without claiming its effect`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script = Script(Read(DesktopDaemonSetting.ENABLED), Read(DesktopDaemonSetting.ENABLED))
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        val before = Files.readString(record(target))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        assertEquals(before, Files.readString(record(target)))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        script.exhausted()
    }

    @Test
    fun `login republishes lost flag while retaining original ownership`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script =
            Script(
                Read(DesktopDaemonSetting.ABSENT),
                Enable,
                Read(DesktopDaemonSetting.ENABLED),
                Read(DesktopDaemonSetting.ABSENT),
                Enable,
                Read(DesktopDaemonSetting.ENABLED),
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        val before = Files.readString(record(target))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        assertEquals(before, Files.readString(record(target)))
        script.exhausted()
    }

    @Test
    fun `conflicting flag is rejected without ownership or mutation`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script = Script(Read(DesktopDaemonSetting.CONFLICTING))
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT),
            DesktopDaemonDiscovery(script).enable(target),
        )
        assertFalse(Files.exists(record(target)))
        script.exhausted()
    }

    @Test
    fun `failed publication retains starting fact for recovery`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script = Script(Read(DesktopDaemonSetting.ABSENT), FailedEnable(DesktopDiscoveryFailure.COMMAND_REJECTED))
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED),
            DesktopDaemonDiscovery(script).enable(target),
        )
        assertTrue(Files.exists(record(target)))
        script.exhausted()
    }

    @Test
    fun `publication requires read back proof`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script = Script(Read(DesktopDaemonSetting.ABSENT), Enable, Read(DesktopDaemonSetting.ABSENT))
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.READ_BACK_REJECTED),
            DesktopDaemonDiscovery(script).enable(target),
        )
        assertTrue(Files.exists(record(target)))
        script.exhausted()
    }

    @Test
    fun `changed flag is preserved during cleanup`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script =
            Script(
                Read(DesktopDaemonSetting.ABSENT),
                Enable,
                Read(DesktopDaemonSetting.ENABLED),
                Read(DesktopDaemonSetting.CONFLICTING),
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        assertFalse(Files.exists(record(target)))
        script.exhausted()
    }

    @Test
    fun `failed cleanup retains ownership for retry`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script =
            Script(
                Read(DesktopDaemonSetting.ABSENT),
                Enable,
                Read(DesktopDaemonSetting.ENABLED),
                Read(DesktopDaemonSetting.ENABLED),
                Remove,
                Read(DesktopDaemonSetting.ENABLED),
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.READ_BACK_REJECTED),
            discovery.release(target),
        )
        assertTrue(Files.exists(record(target)))
        script.exhausted()
    }

    @Test
    fun `foreign ownership rejects before querying launchd`(@TempDir temporary: Path) {
        val target = target(temporary)
        val setup = Script(Read(DesktopDaemonSetting.ENABLED))
        assertEquals(DesktopDiscoveryOutcome.Ready, DesktopDaemonDiscovery(setup).enable(target))
        setup.exhausted()
        val foreign = target.copy(identity = identity('b'))
        val script = Script()
        val discovery = DesktopDaemonDiscovery(script)
        val rejected = DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.OWNER_MISMATCH)
        val before = Files.readString(record(target))
        assertEquals(rejected, discovery.enable(foreign))
        assertEquals(rejected, discovery.enable(DesktopDiscoveryTarget.CleanupOnly(foreign)))
        assertEquals(rejected, discovery.release(foreign))
        assertEquals(before, Files.readString(record(target)))
        script.exhausted()
    }

    @Test
    fun `malformed ownership rejects before querying launchd`(@TempDir temporary: Path) {
        val target = target(temporary)
        val setup = Script(Read(DesktopDaemonSetting.ENABLED))
        assertEquals(DesktopDiscoveryOutcome.Ready, DesktopDaemonDiscovery(setup).enable(target))
        setup.exhausted()
        Files.writeString(record(target), "not-json")
        val script = Script()
        val discovery = DesktopDaemonDiscovery(script)
        val rejected = DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.RECORD_MALFORMED)
        assertEquals(rejected, discovery.enable(target))
        assertEquals(rejected, discovery.enable(DesktopDiscoveryTarget.CleanupOnly(target)))
        assertEquals(rejected, discovery.release(target))
        assertEquals("not-json", Files.readString(record(target)))
        script.exhausted()
    }

    @Test
    fun `private and older installations require no environment effects`(@TempDir temporary: Path) {
        val script = Script()
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(DesktopDiscoveryTarget.NotRequired))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(DesktopDiscoveryTarget.NotRequired))
        val target = target(temporary)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        assertFalse(Files.exists(target.directory.resolve("desktop-discovery")))
        script.exhausted()
    }

    @Test
    fun `startup emits exact success and failure stages`(@TempDir temporary: Path) {
        val events = mutableListOf<BrokerStartupActivity>()
        val publisher = BrokerStartupActivityPublisher {
            events += it
            BrokerStartupActivityPublication.PUBLISHED
        }
        val ready = Script(Read(DesktopDaemonSetting.ENABLED))
        assertEquals(
            DesktopDiscoveryOutcome.Ready,
            InstalledCoordinator.publishDesktopDiscovery(target(temporary), publisher, DesktopDaemonDiscovery(ready)),
        )
        ready.exhausted()
        assertEquals(
            listOf(
                BrokerStartupActivity.Started(BrokerStartupStage.DESKTOP_DISCOVERY),
                BrokerStartupActivity.Completed(BrokerStartupStage.DESKTOP_DISCOVERY),
            ),
            events,
        )
        events.clear()
        val failed = Script(Read(DesktopDaemonSetting.CONFLICTING))
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT),
            InstalledCoordinator.publishDesktopDiscovery(target(temporary), publisher, DesktopDaemonDiscovery(failed)),
        )
        failed.exhausted()
        assertEquals(
            listOf(
                BrokerStartupActivity.Started(BrokerStartupStage.DESKTOP_DISCOVERY),
                BrokerStartupActivity.Rejected(
                    BrokerStartupStage.DESKTOP_DISCOVERY,
                    BrokerStartupRejection.DesktopDiscovery(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT),
                ),
            ),
            events,
        )
    }

    @Test
    fun `launchctl absence enabled conflict and rejection retain distinct meanings`() {
        assertEquals(
            DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ABSENT),
            LaunchdDesktopDaemonEnvironment.interpretRead(0, ""),
        )
        assertEquals(
            DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ENABLED),
            LaunchdDesktopDaemonEnvironment.interpretRead(0, "1\n"),
        )
        for (output in listOf("0\n", "1", "\n", "true\n")) assertEquals(
            DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.CONFLICTING),
            LaunchdDesktopDaemonEnvironment.interpretRead(0, output),
        )
        for (exitCode in listOf(1, 2, -1)) assertEquals(
            DesktopDaemonEnvironmentRead.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED),
            LaunchdDesktopDaemonEnvironment.interpretRead(exitCode, ""),
        )
    }

    @Test
    fun `cleanup only target withdraws owned discovery without republishing`(@TempDir temporary: Path) {
        val target = target(temporary)
        val script =
            Script(
                Read(DesktopDaemonSetting.ABSENT),
                Enable,
                Read(DesktopDaemonSetting.ENABLED),
                Read(DesktopDaemonSetting.ENABLED),
                Remove,
                Read(DesktopDaemonSetting.ABSENT),
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        val cleanup = DesktopDiscoveryTarget.CleanupOnly(target)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(cleanup))
        assertFalse(Files.exists(record(target)))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(cleanup))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(cleanup))
        script.exhausted()
    }

    private fun target(temporary: Path): DesktopDiscoveryTarget.Managed {
        val directory = temporary.toRealPath()
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        return DesktopDiscoveryTarget.Managed(directory, identity('a'))
    }

    private fun identity(digit: Char): BrokerServiceIdentity =
        checkNotNull(BrokerServiceIdentity.admit("sha256:" + digit.toString().repeat(64)))

    private fun record(target: DesktopDiscoveryTarget.Managed): Path =
        target.directory.resolve("desktop-discovery/ownership.json")

    private sealed interface Step

    private data class Read(val setting: DesktopDaemonSetting) : Step

    private data object Enable : Step

    private data object Remove : Step

    private data class FailedEnable(val failure: DesktopDiscoveryFailure) : Step

    private class Script(vararg steps: Step) : DesktopDaemonEnvironment {
        private val remaining = ArrayDeque(steps.toList())

        override fun read(): DesktopDaemonEnvironmentRead {
            val next = remaining.removeFirstOrNull()
            assertTrue(next is Read, "Unexpected read: $next")
            return DesktopDaemonEnvironmentRead.Observed((next as Read).setting)
        }

        override fun enable(): DesktopDiscoveryOutcome =
            when (val next = remaining.removeFirstOrNull()) {
                Enable -> DesktopDiscoveryOutcome.Ready
                is FailedEnable -> DesktopDiscoveryOutcome.Rejected(next.failure)
                else -> throw AssertionError("Unexpected enable: $next")
            }

        override fun remove(): DesktopDiscoveryOutcome {
            assertEquals(Remove, remaining.removeFirstOrNull(), "Unexpected remove")
            return DesktopDiscoveryOutcome.Ready
        }

        fun exhausted() = assertTrue(remaining.isEmpty(), "Unconsumed effects: $remaining")
    }
}
