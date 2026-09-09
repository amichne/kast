package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.host.CodexDesktopExecutable
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.ClosedFileSystemException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class DesktopAttachmentFailure { OVERRIDE_CONFLICT, VERSION_UNSUPPORTED, DESKTOP_UNAVAILABLE, INSPECTION_REJECTED }
internal sealed interface DesktopAttachmentAdmission {
    /** Build-specific discovery eligibility; this is not the interactive desktop release gate. */
    data object Eligible : DesktopAttachmentAdmission
    data class Rejected(val failure: DesktopAttachmentFailure) : DesktopAttachmentAdmission
}
internal object DesktopAttachmentPolicy {
    fun admit(environment: Map<String,String>, version: String, bundledGit: Boolean): DesktopAttachmentAdmission = when {
        environment["CODEX_APP_SERVER_FORCE_CLI"] == "1" || !environment["CODEX_CLI_PATH"].isNullOrEmpty() || bundledGit ->
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.OVERRIDE_CONFLICT)
        version != "26.901.51231" -> DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.VERSION_UNSUPPORTED)
        else -> DesktopAttachmentAdmission.Eligible
    }
}
/** Commands are an explicit effect boundary: native tools cannot read a Jimfs path. */
internal sealed interface DesktopInspectionCommand {
    data class ReadVersion(val plist: Path) : DesktopInspectionCommand
    data class ReadOverride(val name: DesktopOverride) : DesktopInspectionCommand
}
internal enum class DesktopOverride { CODEX_CLI_PATH, CODEX_APP_SERVER_FORCE_CLI }
internal sealed interface DesktopInspectionRead {
    data class Value(val text: String) : DesktopInspectionRead
    data object Rejected : DesktopInspectionRead
}
internal fun interface DesktopInspectionCommands {
    fun read(command: DesktopInspectionCommand): DesktopInspectionRead
}
internal fun interface DesktopAttachmentReporter {
    fun report(admission: DesktopAttachmentAdmission)
}
internal object StderrDesktopAttachmentReporter : DesktopAttachmentReporter {
    override fun report(admission: DesktopAttachmentAdmission) {
        System.err.println(buildJsonObject {
            put("component", "kast-broker")
            put("stage", "desktop-inspection")
            when (admission) {
                DesktopAttachmentAdmission.Eligible -> put("outcome", "eligible")
                is DesktopAttachmentAdmission.Rejected -> {
                    put("outcome", "rejected")
                    put("reason", admission.failure.name.lowercase().replace('_', '-'))
                }
            }
        })
    }
}
/** A discovered bundle retains its provider and a proven metadata file. */
internal class DesktopBundle private constructor(val path: Path) {
    companion object {
        fun discover(environment: Map<String, String>, userHome: Path): Refinement<DesktopBundle, DesktopAttachmentFailure> {
            val filesystem = userHome.fileSystem
            val explicit = environment["KAST_CODEX_DESKTOP_EXECUTABLE"]
            if (explicit != null) {
                val candidate = filesystem.getPath(explicit)
                if (!candidate.isAbsolute || candidate.normalize() != candidate) {
                    return Refinement.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
                }
                val executable = when (val admitted = CodexDesktopExecutable.admit(candidate)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return Refinement.Rejected(DesktopAttachmentFailure.DESKTOP_UNAVAILABLE)
                }
                val path = executable.path
                // The explicit launch target must belong to an inspectable macOS bundle.
                if (path.nameCount < 4 || path.parent.fileName.toString() != "MacOS" ||
                    path.parent.parent.fileName.toString() != "Contents" ||
                    !path.parent.parent.parent.fileName.toString().endsWith(".app")) {
                    return Refinement.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
                }
                return admit(path.parent.parent.parent)
            }
            // Preserve the existing search order and the caller's filesystem provider.
            for (candidate in listOf(
                filesystem.getPath("/Applications/ChatGPT.app"),
                filesystem.getPath("/Applications/Codex.app"),
                userHome.resolve("Applications/Codex.app"),
            )) {
                if (Files.isRegularFile(candidate.resolve("Contents/Info.plist"))) return admit(candidate)
            }
            return Refinement.Rejected(DesktopAttachmentFailure.DESKTOP_UNAVAILABLE)
        }

        private fun admit(path: Path): Refinement<DesktopBundle, DesktopAttachmentFailure> =
            if (Files.isRegularFile(path.resolve("Contents/Info.plist"))) Refinement.Refined(DesktopBundle(path))
            else Refinement.Rejected(DesktopAttachmentFailure.DESKTOP_UNAVAILABLE)
    }
}

internal object InstalledDesktopAttachmentProbe {
    fun inspect(
        environment: Map<String, String>,
        userHome: Path,
        commands: DesktopInspectionCommands = NativeDesktopInspectionCommands,
        reporter: DesktopAttachmentReporter = StderrDesktopAttachmentReporter,
    ): DesktopAttachmentAdmission {
        val admission = try {
            inspectFiles(environment, userHome, commands)
        } catch (_: IOException) {
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
        } catch (_: SecurityException) {
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
        } catch (_: InvalidPathException) {
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
        } catch (_: ClosedFileSystemException) {
            DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
        }
        reporter.report(admission)
        return admission
    }

    private fun inspectFiles(
        environment: Map<String, String>,
        userHome: Path,
        commands: DesktopInspectionCommands,
    ): DesktopAttachmentAdmission {
        val bundle = when (val selected = DesktopBundle.discover(environment, userHome)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return DesktopAttachmentAdmission.Rejected(selected.failure)
        }
        val version = when (val read = commands.read(DesktopInspectionCommand.ReadVersion(bundle.path.resolve("Contents/Info.plist")))) {
            is DesktopInspectionRead.Value -> read.text
            DesktopInspectionRead.Rejected -> return DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
        }
        val effective = environment.toMutableMap()
        for (name in DesktopOverride.entries) {
            when (val read = commands.read(DesktopInspectionCommand.ReadOverride(name))) {
                is DesktopInspectionRead.Value -> if (read.text.isNotEmpty()) effective[name.name] = read.text
                DesktopInspectionRead.Rejected -> return DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
            }
        }
        return DesktopAttachmentPolicy.admit(effective, version, Files.exists(bundle.path.resolve("Contents/Resources/git/bin/git")))
    }
}

internal object NativeDesktopInspectionCommands : DesktopInspectionCommands {
    override fun read(command: DesktopInspectionCommand): DesktopInspectionRead {
        val arguments = when (command) {
            is DesktopInspectionCommand.ReadVersion -> listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", command.plist.toString())
            is DesktopInspectionCommand.ReadOverride -> listOf("/bin/launchctl", "getenv", command.name.name)
        }
        val process = try {
            ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: IOException) {
            return DesktopInspectionRead.Rejected
        } catch (_: SecurityException) {
            return DesktopInspectionRead.Rejected
        }
        return try {
            if (!process.waitFor(BrokerOperationalLimits.desktopInspection.value, TimeUnit.MILLISECONDS)) return DesktopInspectionRead.Rejected
            val output = process.inputStream.use { it.readNBytes(BrokerOperationalLimits.maximumDesktopInspectionBytes + 1) }
            val acceptedExit = when (command) {
                is DesktopInspectionCommand.ReadVersion -> process.exitValue() == 0
                is DesktopInspectionCommand.ReadOverride -> process.exitValue() in 0..1
            }
            if (!acceptedExit || output.size > BrokerOperationalLimits.maximumDesktopInspectionBytes) DesktopInspectionRead.Rejected
            else DesktopInspectionRead.Value(output.toString(Charsets.UTF_8).trimEnd('\n', '\r'))
        } catch (_: IOException) {
            DesktopInspectionRead.Rejected
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DesktopInspectionRead.Rejected
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }
}
