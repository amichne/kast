package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticServiceLaunchTest {
    @Test
    fun `semantic startup submits the installed Control to the owned launchd domain`() =
        withCoordinatorPayload { root, _ ->
            val executable = root.resolve("share/kast/libexec/kast-management")
            Files.createDirectories(executable.parent)
            Files.writeString(executable, "fixture")
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            val command =
                checkNotNull(
                    SemanticServiceLaunch.admit(
                        root,
                        root,
                        mapOf(
                            "KAST_HOST_MAX_HEAP_MIB" to "2048",
                            "KAST_OPTS" to "-DownedFixture=omit",
                            "UNRELATED_TOKEN" to "omit",
                        ),
                    )
                )
            val launches = mutableListOf<String>()
            val host =
                MacOsPersistentBrokerServiceHost(
                    launchctl =
                        LaunchctlInvoker { arguments, _ ->
                            val action = arguments[1]
                            launches += action
                            when (action) {
                                "list" -> LaunchctlInvocation.Absent
                                "bootstrap" -> {
                                    assertEquals("gui/" + Files.getAttribute(root, "unix:uid"), arguments[2])
                                    val plist = command.state.resolve("semantic-service.plist")
                                    assertEquals(plist.toString(), arguments[3])
                                    val document = Files.readString(plist)
                                    assertTrue(document.contains(executable.toString()))
                                    assertTrue(document.contains("<string>--semantic</string>"))
                                    assertTrue(document.contains("<string>KAST_HOST_MAX_HEAP_MIB=2048</string>"))
                                    assertTrue(document.contains("<key>StandardInPath</key><string>/dev/null</string>"))
                                    assertFalse(document.contains("UNRELATED_TOKEN"))
                                    assertFalse(document.contains("KAST_OPTS"))
                                    LaunchctlInvocation.Completed
                                }
                                else -> error("Unexpected launchctl action: $action")
                            }
                        }
                )
            assertEquals(io.github.amichne.kast.kernel.Refinement.Refined(Unit), host.submitSemantic(command))
            assertEquals(listOf("list", "bootstrap"), launches)
        }

    @Test
    fun `semantic startup submits an independent systemd user service`() = withCoordinatorPayload { root, _ ->
        val executable = root.resolve("share/kast/libexec/kast-management")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, "fixture")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val command = checkNotNull(SemanticServiceLaunch.admit(root, root, mapOf("TMPDIR" to "/tmp/\$literal")))
        val calls = mutableListOf<List<String>>()
        val host = LinuxPersistentBrokerServiceHost { arguments, _ ->
            calls += arguments
            when (arguments.first()) {
                "/usr/bin/systemctl" -> LaunchctlInvocation.Absent
                "/usr/bin/systemd-run" -> LaunchctlInvocation.Completed
                else -> error("Unexpected systemd executable: ${arguments.first()}")
            }
        }
        assertEquals(io.github.amichne.kast.kernel.Refinement.Refined(Unit), host.submitSemantic(command))
        assertEquals(listOf("/usr/bin/systemctl", "/usr/bin/systemd-run"), calls.map { it.first() })
        assertEquals(
            listOf(
                "/usr/bin/systemd-run",
                "--user",
                "--quiet",
                "--collect",
                "--unit=${command.label}.service",
                "--property=Type=exec",
                "--property=WorkingDirectory=$root",
                "--property=StandardInput=null",
                "--property=StandardOutput=journal",
                "--property=StandardError=journal",
                "--",
            ),
            calls[1].takeWhile { it != "--" } + "--",
        )
        assertTrue(calls[1].contains("TMPDIR=/tmp/\$\$literal"))
        assertTrue(calls[1].contains(executable.toString()))
        assertEquals("--semantic", calls[1].last())
    }
}
