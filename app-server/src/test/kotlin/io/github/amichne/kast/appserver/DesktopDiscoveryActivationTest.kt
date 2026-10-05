package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopDiscoveryActivationTest {
    @Test
    fun `custom home rejects publication when desktop inherits default home`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = target(root, root.resolve("custom"))
        val script = Script(DesktopDaemonHomeRead.Default, "home")
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.HOME_MISMATCH),
            DesktopDaemonDiscovery(script).enable(target),
        )
        assertFalse(Files.exists(record(target)))
        script.exhausted()
    }

    @Test
    fun `default broker rejects foreign or unavailable desktop routing`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = target(root)
        val cases =
            listOf(
                DesktopDaemonHomeRead.Configured(CodexControlSocketPath.from(root.resolve("other"))) to
                    DesktopDiscoveryFailure.HOME_MISMATCH,
                DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED) to
                    DesktopDiscoveryFailure.COMMAND_REJECTED,
            )
        for ((observation, failure) in cases) {
            val script = Script(observation, "home")
            assertEquals(DesktopDiscoveryOutcome.Rejected(failure), DesktopDaemonDiscovery(script).enable(target))
            assertFalse(Files.exists(record(target)))
            script.exhausted()
        }
    }

    @Test
    fun `explicit desktop home must match served canonical socket`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = root.resolve("custom")
        val target = target(root, home)
        val script =
            Script(
                DesktopDaemonHomeRead.Configured(CodexControlSocketPath.from(home)),
                "home",
                "read:ABSENT",
                "enable",
                "read:ENABLED",
                "read:ENABLED",
                "remove",
                "read:ABSENT",
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        script.exhausted()
    }

    @Test
    fun `removed preexisting flag is not republished with discarded ownership`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = target(root)
        val script = Script(DesktopDaemonHomeRead.Default, "home", "read:ENABLED", "home", "read:ABSENT")
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        val prior = Files.readString(record(target))
        assertEquals(
            DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT),
            discovery.enable(target),
        )
        assertEquals(prior, Files.readString(record(target)))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        script.exhausted()
    }

    @Test
    fun `successful readiness retains discovery until owned release`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = target(root)
        val path = root.resolve("service-readiness.json")
        val managed = requireNotNull(BrokerServiceReadiness.Managed.admit(target.owner.identity.value, path, path))
        val readiness = (managed.begin() as BrokerReadinessBeginning.Begun).owned
        val script =
            Script(
                DesktopDaemonHomeRead.Default,
                "home",
                "read:ABSENT",
                "enable",
                "read:ENABLED",
                "read:ENABLED",
                "remove",
                "read:ABSENT",
            )
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        val activities = mutableListOf<BrokerStartupActivity>()
        val activity =
            BrokerStartupActivityPublisher(
                BrokerStartupActivitySink {
                    activities += it
                    BrokerStartupActivityPublication.PUBLISHED
                }
            )
        assertEquals(
            CoordinatorReadinessPublication.Ready,
            CoordinatorReadinessPublisher.publish(readiness, target, discovery, activity),
        )
        val state = readServiceState(path)
        assertTrue(state is BrokerServiceStateDocument.Ready)
        assertEquals(target.owner.identity.value, (state as BrokerServiceStateDocument.Ready).serviceIdentity)
        assertTrue(Files.exists(record(target)))
        assertFalse(activities.any { it.stage == BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP })
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(target))
        script.exhausted()
    }

    @Test
    fun `rejected readiness removes just published discovery`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        rejectReadiness(root, "remove", BrokerServerFailure.READINESS_REJECTED, false)
    }

    @Test
    fun `readiness rollback failure retains ownership and both rejection causes`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        rejectReadiness(root, "remove:REJECTED", BrokerServerFailure.DESKTOP_DISCOVERY_REJECTED, true)
    }

    @Test
    fun `home adapter rejects malformed and failed observations`() {
        assertEquals(DesktopDaemonHomeRead.Default, LaunchdDesktopDaemonEnvironment.interpretHome(0, ""))
        val configured = LaunchdDesktopDaemonEnvironment.interpretHome(0, "/tmp/codex\n")
        assertTrue(configured is DesktopDaemonHomeRead.Configured)
        assertEquals(
            Path.of("/tmp/codex/app-server-control/app-server-control.sock"),
            (configured as DesktopDaemonHomeRead.Configured).socket.path,
        )
        for (raw in listOf("relative\n", "/tmp/../codex\n", "/tmp/codex", "/tmp\n/codex\n", "\u0000\n")) {
            assertEquals(
                DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.HOME_REJECTED),
                LaunchdDesktopDaemonEnvironment.interpretHome(0, raw),
            )
        }
        assertEquals(
            DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED),
            LaunchdDesktopDaemonEnvironment.interpretHome(1, ""),
        )
    }

    private fun rejectReadiness(root: Path, removal: String, failure: BrokerServerFailure, retains: Boolean) {
        val target = target(root)
        val path = root.resolve("service-readiness.json")
        val managed = requireNotNull(BrokerServiceReadiness.Managed.admit(target.owner.identity.value, path, path))
        val readiness = (managed.begin() as BrokerReadinessBeginning.Begun).owned
        val steps =
            listOf("home", "read:ABSENT", "enable", "read:ENABLED", "read:ENABLED", removal) +
                if (retains) emptyList() else listOf("read:ABSENT")
        val script = Script(DesktopDaemonHomeRead.Default, *steps.toTypedArray())
        val discovery = DesktopDaemonDiscovery(script)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(target))
        Files.writeString(path, "deliberately incompatible readiness")
        val activities = mutableListOf<BrokerStartupActivity>()
        val activity =
            BrokerStartupActivityPublisher(
                BrokerStartupActivitySink {
                    activities += it
                    BrokerStartupActivityPublication.PUBLISHED
                }
            )
        assertEquals(
            CoordinatorReadinessPublication.Rejected(failure),
            CoordinatorReadinessPublisher.publish(readiness, target, discovery, activity),
        )
        assertEquals(retains, Files.exists(record(target)))
        assertEquals("deliberately incompatible readiness", Files.readString(path))
        assertTrue(
            activities.contains(
                BrokerStartupActivity.Rejected(
                    BrokerStartupStage.READINESS_PUBLICATION,
                    BrokerStartupRejection.Coordinator(BrokerServerFailure.READINESS_REJECTED),
                )
            )
        )
        val cleanup =
            if (retains)
                BrokerStartupActivity.Rejected(
                    BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP,
                    BrokerStartupRejection.DesktopDiscovery(DesktopDiscoveryFailure.COMMAND_REJECTED),
                )
            else BrokerStartupActivity.Completed(BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP)
        assertTrue(activities.contains(cleanup))
        script.exhausted()
    }

    private fun target(root: Path, home: Path = root.resolve(".codex")): DesktopDiscoveryTarget.Publish {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val owner = DesktopDiscoveryOwner(root, requireNotNull(BrokerServiceIdentity.admit("sha256:${"a".repeat(64)}")))
        return DesktopDiscoveryTarget.Publish(
            owner,
            CodexControlSocketPath.from(home),
            CodexControlSocketPath.from(root.resolve(".codex")),
        )
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
            assertTrue(next.startsWith("read:"), "Unexpected flag observation: $next")
            return DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.valueOf(next.removePrefix("read:")))
        }

        override fun enable(): DesktopDiscoveryOutcome {
            assertEquals("enable", remaining.removeFirstOrNull(), "Unexpected publication")
            return DesktopDiscoveryOutcome.Ready
        }

        override fun remove(): DesktopDiscoveryOutcome =
            when (val next = remaining.removeFirstOrNull()) {
                "remove" -> DesktopDiscoveryOutcome.Ready
                "remove:REJECTED" -> DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
                else -> throw AssertionError("Unexpected cleanup: $next")
            }

        fun exhausted() = assertTrue(remaining.isEmpty(), "Unconsumed observations: $remaining")
    }
}
