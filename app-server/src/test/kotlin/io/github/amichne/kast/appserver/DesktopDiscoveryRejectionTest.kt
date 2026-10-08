package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopDiscoveryRejectionTest {
    @Test
    fun `rejected publication preserves external ownership across retry and restart`(@TempDir temporary: Path) {
        val target = target(temporary.toRealPath())
        val setup = Script(DesktopDaemonHomeRead.Default, "home", "read:ENABLED")
        assertEquals(DesktopDiscoveryOutcome.Ready, DesktopDaemonDiscovery(setup).enable(target))
        setup.exhausted()
        val prior = Files.readString(record(target))
        val steps = List(3) { listOf("home", "read:ABSENT") }.flatten()
        val script = Script(DesktopDaemonHomeRead.Default, *steps.toTypedArray())
        val discovery = DesktopDaemonDiscovery(script)
        val activities = mutableListOf<BrokerStartupActivity>()
        val activity = BrokerStartupActivityPublisher { event ->
            activities += event
            BrokerStartupActivityPublication.PUBLISHED
        }
        repeat(3) { attempt ->
            val current = if (attempt == 2) DesktopDaemonDiscovery(script) else discovery
            assertEquals(
                DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT),
                InstalledCoordinator.publishDesktopDiscovery(target, activity, current),
            )
            assertTrue(Files.exists(record(target)), "Rejected publication discarded external ownership")
            assertEquals(prior, Files.readString(record(target)))
        }
        assertEquals(
            3,
            activities.count { event ->
                event ==
                    BrokerStartupActivity.Rejected(
                        BrokerStartupStage.DESKTOP_DISCOVERY,
                        BrokerStartupRejection.DesktopDiscovery(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT),
                    )
            },
        )
        assertFalse(activities.any { it.stage == BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP })
        script.exhausted()

        val restored = Script(DesktopDaemonHomeRead.Default, "home", "read:ENABLED")
        val restarted = DesktopDaemonDiscovery(restored)
        assertEquals(
            DesktopDiscoveryOutcome.Ready,
            InstalledCoordinator.publishDesktopDiscovery(target, activity, restarted),
        )
        assertEquals(prior, Files.readString(record(target)))
        assertEquals(DesktopDiscoveryOutcome.Ready, restarted.release(target))
        assertFalse(Files.exists(record(target)))
        restored.exhausted()
    }

    @Test
    fun `home rejection preserves a retained owned flag and record`(@TempDir temporary: Path) {
        val target = target(temporary.toRealPath())
        val setup = Script(DesktopDaemonHomeRead.Default, "home", "read:ABSENT", "enable", "read:ENABLED")
        assertEquals(DesktopDiscoveryOutcome.Ready, DesktopDaemonDiscovery(setup).enable(target))
        setup.exhausted()
        val prior = Files.readString(record(target))
        val script =
            Script(DesktopDaemonHomeRead.Configured(CodexControlSocketPath.from(temporary.resolve("other"))), "home")
        val activities = mutableListOf<BrokerStartupActivity>()
        val activity = BrokerStartupActivityPublisher { event ->
            activities += event
            BrokerStartupActivityPublication.PUBLISHED
        }
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.HOME_MISMATCH),
            InstalledCoordinator.publishDesktopDiscovery(target, activity, DesktopDaemonDiscovery(script)),
        )
        assertEquals(prior, Files.readString(record(target)))
        assertTrue(
            activities.contains(
                BrokerStartupActivity.Rejected(
                    BrokerStartupStage.DESKTOP_DISCOVERY,
                    BrokerStartupRejection.DesktopDiscovery(DesktopDiscoveryFailure.HOME_MISMATCH),
                )
            )
        )
        assertFalse(activities.any { it.stage == BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP })
        script.exhausted()
    }

    @Test
    fun `setting rejection preserves retained external ownership`(@TempDir temporary: Path) {
        val target = target(temporary.toRealPath())
        val setup = Script(DesktopDaemonHomeRead.Default, "home", "read:ENABLED")
        assertEquals(DesktopDiscoveryOutcome.Ready, DesktopDaemonDiscovery(setup).enable(target))
        setup.exhausted()
        val prior = Files.readString(record(target))
        for ((observation, failure) in
            listOf(
                "read:CONFLICTING" to DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT,
                "read:REJECTED" to DesktopDiscoveryFailure.COMMAND_TIMED_OUT,
            )) {
            val script = Script(DesktopDaemonHomeRead.Default, "home", observation)
            val activities = mutableListOf<BrokerStartupActivity>()
            val activity = BrokerStartupActivityPublisher { event ->
                activities += event
                BrokerStartupActivityPublication.PUBLISHED
            }
            assertEquals(
                DesktopDiscoveryOutcome.Rejected(failure),
                InstalledCoordinator.publishDesktopDiscovery(target, activity, DesktopDaemonDiscovery(script)),
            )
            assertEquals(prior, Files.readString(record(target)))
            assertTrue(
                activities.contains(
                    BrokerStartupActivity.Rejected(
                        BrokerStartupStage.DESKTOP_DISCOVERY,
                        BrokerStartupRejection.DesktopDiscovery(failure),
                    )
                )
            )
            assertFalse(activities.any { it.stage == BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP })
            script.exhausted()
        }
    }

    private fun target(root: Path): DesktopDiscoveryTarget.Publish {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val owner = DesktopDiscoveryOwner(root, requireNotNull(BrokerServiceIdentity.admit("sha256:${"a".repeat(64)}")))
        val socket = CodexControlSocketPath.from(root.resolve(".codex"))
        return DesktopDiscoveryTarget.Publish(owner, socket, socket)
    }

    private fun record(target: DesktopDiscoveryTarget.Publish) =
        target.owner.directory.resolve("desktop-discovery/ownership.json")

    private class Script(private val home: DesktopDaemonHomeRead, vararg steps: String) : DesktopDaemonEnvironment {
        private val remaining = ArrayDeque(steps.toList())

        override fun readHome(): DesktopDaemonHomeRead {
            assertEquals("home", remaining.removeFirstOrNull(), "Unexpected home observation")
            return home
        }

        override fun read(): DesktopDaemonEnvironmentRead {
            val next = remaining.removeFirstOrNull() ?: throw AssertionError("Unexpected flag observation")
            if (next == "read:REJECTED")
                return DesktopDaemonEnvironmentRead.Rejected(DesktopDiscoveryFailure.COMMAND_TIMED_OUT)
            assertTrue(next.startsWith("read:"), "Unexpected flag observation: $next")
            return DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.valueOf(next.removePrefix("read:")))
        }

        override fun enable(): DesktopDiscoveryOutcome {
            assertEquals("enable", remaining.removeFirstOrNull(), "Unexpected publication")
            return DesktopDiscoveryOutcome.Ready
        }

        override fun remove(): DesktopDiscoveryOutcome = throw AssertionError("Unexpected cleanup")

        fun exhausted() = assertTrue(remaining.isEmpty(), "Unconsumed observations: $remaining")
    }
}
