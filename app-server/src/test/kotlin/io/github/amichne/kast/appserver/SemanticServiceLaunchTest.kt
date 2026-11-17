package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
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
                            "GRADLE_USER_HOME" to root.resolve("gradle").toString(),
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
                                    assertTrue(document.contains("<string>GRADLE_USER_HOME=$root/gradle</string>"))
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
    fun `semantic startup starts a loaded launchd job that has no live publication`() =
        withCoordinatorPayload { root, _ ->
            val executable = root.resolve("share/kast/libexec/kast-management")
            Files.createDirectories(executable.parent)
            Files.writeString(executable, "fixture")
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            val command = checkNotNull(SemanticServiceLaunch.admit(root, root, emptyMap()))
            val calls = mutableListOf<List<String>>()
            val target = "gui/${Files.getAttribute(root, "unix:uid")}/${command.label}"
            var publishedJob = launchdOutput(target, command)
            val host =
                MacOsPersistentBrokerServiceHost(
                    semanticJob = { selected -> publishedJob.takeIf { selected == target } },
                    launchctl =
                        LaunchctlInvoker { arguments, _ ->
                            calls += arguments
                            when (arguments[1]) {
                                "list",
                                "kickstart" -> LaunchctlInvocation.Completed
                                else -> error("Unexpected launchctl action: ${arguments[1]}")
                            }
                        },
                )
            publishedJob = publishedJob.replace(command.executable.toString(), "/foreign/kast-management")
            assertEquals(
                Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED),
                host.submitSemantic(command),
            )
            assertEquals(listOf(listOf("/bin/launchctl", "list", command.label)), calls)
            calls.clear()
            publishedJob = launchdOutput(target, command)
            assertEquals(Refinement.Refined(Unit), host.submitSemantic(command))
            assertEquals(
                listOf(
                    listOf("/bin/launchctl", "list", command.label),
                    listOf(
                        "/bin/launchctl",
                        "kickstart",
                        "gui/${Files.getAttribute(root, "unix:uid")}/${command.label}",
                    ),
                ),
                calls,
            )
        }

    private fun launchdOutput(target: String, command: SemanticServiceLaunch): String = buildString {
        appendLine("$target = {")
        appendLine("\tpath = ${command.state.resolve("semantic-service.plist")}")
        appendLine("\tprogram = /usr/bin/env")
        appendLine("\targuments = {")
        command.arguments.forEach { appendLine("\t\t$it") }
        appendLine("\t}")
        appendLine("\tworking directory = ${command.installation}")
        appendLine("}")
    }

    @Test
    fun `semantic startup submits an independent systemd user service`() = withCoordinatorPayload { root, _ ->
        val executable = root.resolve("share/kast/libexec/kast-management")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, "fixture")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val command = checkNotNull(SemanticServiceLaunch.admit(root, root, mapOf("TMPDIR" to "/tmp/\$literal")))
        val runtime = createLinuxUserRuntime(root)
        val calls = mutableListOf<List<String>>()
        val host =
            LinuxPersistentBrokerServiceHost(runtime.parent) { arguments, _ ->
                assertEquals(listOf("/usr/bin/env", "-i", "XDG_RUNTIME_DIR=$runtime"), arguments.take(3))
                val commandArguments = arguments.drop(3)
                calls += commandArguments
                when (commandArguments.first()) {
                    "/usr/bin/systemctl" -> LaunchctlInvocation.Absent
                    "/usr/bin/systemd-run" -> LaunchctlInvocation.Completed
                    else -> error("Unexpected systemd executable: ${commandArguments.first()}")
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

        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertEquals(
            io.github.amichne.kast.kernel.Refinement.Rejected(
                PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED
            ),
            host.submitSemantic(command),
        )
        assertEquals(2, calls.size)
    }

    private fun createLinuxUserRuntime(root: Path): Path {
        val base = Files.createDirectory(root.resolve("run-user"))
        val uid = Files.getAttribute(root, "unix:uid")
        return Files.createDirectory(
            base.resolve(uid.toString()),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    }
}
