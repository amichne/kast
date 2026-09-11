package io.github.amichne.kast.appserver

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopAttachmentTest {
    @Test
    fun `empty injected filesystem cannot discover applications on the host`() {
        Jimfs.newFileSystem(Configuration.unix()).use { filesystem ->
            assertEquals(
                DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.DESKTOP_UNAVAILABLE),
                InstalledDesktopAttachmentProbe.inspect(emptyMap(), filesystem.getPath("/users/test")),
            )
        }
    }

    @Test
    fun `discovery and all command outcomes use the injected filesystem`() {
        for (bundleName in
            listOf("/users/test/Applications/Codex.app", "/Applications/ChatGPT.app", "/Applications/Codex.app")) {
            Jimfs.newFileSystem(Configuration.unix()).use { filesystem ->
                val home = filesystem.getPath("/users/test")
                val bundle = filesystem.getPath(bundleName)
                val plist = bundle.resolve("Contents/Info.plist")
                Files.createDirectories(plist.parent)
                Files.writeString(plist, "fixture")
                val commands = DesktopInspectionCommands { command ->
                    when (command) {
                        is DesktopInspectionCommand.ReadVersion -> {
                            assertEquals(plist, command.plist)
                            DesktopInspectionRead.Value("26.901.51231")
                        }
                        is DesktopInspectionCommand.ReadOverride -> DesktopInspectionRead.Value("")
                    }
                }
                val observations = mutableListOf<DesktopAttachmentAdmission>()
                val reporter = DesktopAttachmentReporter { observations.add(it) }
                assertEquals(
                    DesktopAttachmentAdmission.Eligible,
                    InstalledDesktopAttachmentProbe.inspect(emptyMap(), home, commands, reporter),
                )
                val git = bundle.resolve("Contents/Resources/git/bin/git")
                Files.createDirectories(git.parent)
                Files.writeString(git, "fixture")
                val conflict = DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.OVERRIDE_CONFLICT)
                assertEquals(conflict, InstalledDesktopAttachmentProbe.inspect(emptyMap(), home, commands, reporter))
                assertEquals(listOf(DesktopAttachmentAdmission.Eligible, conflict), observations)
                Files.delete(git)
                for (failed in
                    listOf(
                        DesktopInspectionCommand.ReadVersion(plist),
                        DesktopInspectionCommand.ReadOverride(DesktopOverride.CODEX_CLI_PATH),
                        DesktopInspectionCommand.ReadOverride(DesktopOverride.CODEX_APP_SERVER_FORCE_CLI),
                    )) {
                    val failing = DesktopInspectionCommands {
                        if (it == failed) DesktopInspectionRead.Rejected else commands.read(it)
                    }
                    assertEquals(
                        DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED),
                        InstalledDesktopAttachmentProbe.inspect(emptyMap(), home, failing, reporter),
                    )
                }
                for (override in DesktopOverride.entries) {
                    val conflicting = DesktopInspectionCommands {
                        if (it == DesktopInspectionCommand.ReadOverride(override)) DesktopInspectionRead.Value("1")
                        else commands.read(it)
                    }
                    assertEquals(
                        conflict,
                        InstalledDesktopAttachmentProbe.inspect(emptyMap(), home, conflicting, reporter),
                    )
                }
            }
        }
    }

    @Test
    fun `global discovery order is preserved without falling back from rejection`() {
        Jimfs.newFileSystem(Configuration.unix()).use { filesystem ->
            val home = filesystem.getPath("/users/test")
            val local = home.resolve("Applications/Codex.app/Contents/Info.plist")
            val global = filesystem.getPath("/Applications/ChatGPT.app/Contents/Info.plist")
            for (plist in listOf(local, global)) {
                Files.createDirectories(plist.parent)
                Files.writeString(plist, "fixture")
            }
            val commands = DesktopInspectionCommands {
                when (it) {
                    is DesktopInspectionCommand.ReadVersion -> {
                        assertEquals(global, it.plist)
                        DesktopInspectionRead.Value("unsupported")
                    }
                    is DesktopInspectionCommand.ReadOverride -> DesktopInspectionRead.Value("")
                }
            }
            assertEquals(
                DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.VERSION_UNSUPPORTED),
                InstalledDesktopAttachmentProbe.inspect(emptyMap(), home, commands),
            )
        }
    }

    @Test
    fun `explicit desktop selection inspects only its admitted bundle`() {
        Jimfs.newFileSystem(Configuration.unix()).use { filesystem ->
            val home = filesystem.getPath("/users/test")
            val executable = home.resolve("Applications/Codex.app/Contents/MacOS/Codex")
            val plist = executable.parent.parent.resolve("Info.plist")
            Files.createDirectories(executable.parent)
            Files.writeString(executable, "fixture")
            Files.writeString(plist, "fixture")
            val commands = DesktopInspectionCommands {
                when (it) {
                    is DesktopInspectionCommand.ReadVersion -> {
                        assertEquals(plist, it.plist)
                        DesktopInspectionRead.Value("26.901.51231")
                    }
                    is DesktopInspectionCommand.ReadOverride -> DesktopInspectionRead.Value("")
                }
            }
            val global = filesystem.getPath("/Applications/ChatGPT.app/Contents/Info.plist")
            Files.createDirectories(global.parent)
            Files.writeString(global, "must not be selected")
            val environment = mapOf("KAST_CODEX_DESKTOP_EXECUTABLE" to executable.toString())
            assertEquals(
                DesktopAttachmentAdmission.Eligible,
                InstalledDesktopAttachmentProbe.inspect(environment, home, commands),
            )
            Files.delete(plist)
            assertEquals(
                DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.DESKTOP_UNAVAILABLE),
                InstalledDesktopAttachmentProbe.inspect(
                    environment,
                    home,
                    DesktopInspectionCommands { error("missing metadata must not execute commands") },
                ),
            )
            for (invalid in listOf("relative", "/users/../test", "bad\u0000path")) {
                assertEquals(
                    DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED),
                    InstalledDesktopAttachmentProbe.inspect(
                        mapOf("KAST_CODEX_DESKTOP_EXECUTABLE" to invalid),
                        home,
                        commands,
                    ),
                )
            }
        }
    }

    @Test
    fun `closed filesystem reports inspection rejection without invoking commands`() {
        val filesystem = Jimfs.newFileSystem(Configuration.unix())
        val home = filesystem.getPath("/users/test")
        filesystem.close()
        val observations = mutableListOf<DesktopAttachmentAdmission>()
        val expected = DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
        assertEquals(
            expected,
            InstalledDesktopAttachmentProbe.inspect(
                emptyMap(),
                home,
                DesktopInspectionCommands { error("closed filesystem must not execute commands") },
                DesktopAttachmentReporter { observations.add(it) },
            ),
        )
        assertEquals(listOf(expected), observations)
    }

    @Test
    fun `unknown builds and competing discovery mechanisms are explicit incompatibilities`() {
        assertEquals(
            DesktopAttachmentAdmission.Eligible,
            DesktopAttachmentPolicy.admit(emptyMap(), "26.901.51231", false),
        )
        assertEquals(
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.VERSION_UNSUPPORTED),
            DesktopAttachmentPolicy.admit(emptyMap(), "next", false),
        )
        for (overrides in listOf(mapOf("CODEX_CLI_PATH" to "/other"), mapOf("CODEX_APP_SERVER_FORCE_CLI" to "1"))) {
            assertEquals(
                DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.OVERRIDE_CONFLICT),
                DesktopAttachmentPolicy.admit(overrides, "26.901.51231", false),
            )
        }
        assertEquals(
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.OVERRIDE_CONFLICT),
            DesktopAttachmentPolicy.admit(emptyMap(), "26.901.51231", true),
        )
    }
}
