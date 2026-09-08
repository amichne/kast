package io.github.amichne.kast.appserver

import java.nio.file.Files
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
internal object InstalledDesktopAttachmentProbe {
    fun inspect(environment: Map<String,String>, userHome: Path): DesktopAttachmentAdmission {
        val bundle = listOf(Path.of("/Applications/ChatGPT.app"),Path.of("/Applications/Codex.app"),userHome.resolve("Applications/Codex.app"))
            .firstOrNull { Files.isRegularFile(it.resolve("Contents/Info.plist")) }
            ?: return DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.DESKTOP_UNAVAILABLE)
        return try {
            val version = readCommand(listOf("/usr/libexec/PlistBuddy","-c","Print :CFBundleShortVersionString",bundle.resolve("Contents/Info.plist").toString()),setOf(0))
                ?: return DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
            val effective = environment.toMutableMap()
            for (name in listOf("CODEX_CLI_PATH","CODEX_APP_SERVER_FORCE_CLI")) {
                val gui = readCommand(listOf("/bin/launchctl","getenv",name),setOf(0,1))
                    ?: return DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED)
                if (gui.isNotEmpty()) effective[name] = gui
            }
            DesktopAttachmentPolicy.admit(effective,version,Files.exists(bundle.resolve("Contents/Resources/git/bin/git")))
        } catch (_: Exception) { DesktopAttachmentAdmission.Rejected(DesktopAttachmentFailure.INSPECTION_REJECTED) }
    }
    private fun readCommand(command: List<String>, exits: Set<Int>): String? {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
        val output = process.inputStream.use { it.readNBytes(1_025) }
        if (process.exitValue() !in exits || output.size > 1_024) return null
        return output.toString(Charsets.UTF_8).trimEnd('\n','\r')
    }
}
