package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.distribution.managed.SelectedIdeLayout
import java.util.concurrent.TimeUnit

internal enum class IdeRunningProcess {
    PRESENT,
    ABSENT,
    UNAVAILABLE,
}

internal fun runningIde(
    launch: SelectedIdeLaunch.Resolved,
    platform: IdeLaunchPlatform,
    deployment: SelectedLifecycleDeployment,
): IdeRunningProcess {
    if (launch.layout == SelectedIdeLayout.LINUX_DIRECTORY || deployment is SelectedLifecycleDeployment.Headless)
        return runningDirect(launch, platform)
    // Read native application identity; no activation or accessibility control.
    val script =
        """ObjC.import('AppKit'); function run(argv) { var apps = $.NSWorkspace.sharedWorkspace.runningApplications; for (var i = 0; i < apps.count; i++) { var a = apps.objectAtIndex(i); if (a.bundleURL && ObjC.unwrap(a.bundleURL.path) === argv[0]) return 'present'; } return 'absent'; }"""
    return try {
        val process =
            ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", script, launch.bundle)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        if (!process.waitFor(DISCOVERY_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            IdeRunningProcess.UNAVAILABLE
        } else if (process.exitValue() != 0) IdeRunningProcess.UNAVAILABLE
        else
            when (process.inputStream.readNBytes(MAX_RUNNING_REPLY).decodeToString().trim()) {
                "present" -> IdeRunningProcess.PRESENT
                "absent" -> IdeRunningProcess.ABSENT
                else -> IdeRunningProcess.UNAVAILABLE
            }
    } catch (_: java.io.IOException) {
        IdeRunningProcess.UNAVAILABLE
    }
}

private fun runningDirect(launch: SelectedIdeLaunch.Resolved, platform: IdeLaunchPlatform): IdeRunningProcess {
    return try {
        val observed = platform.processes()
        if (observed.size > MAX_RUNNING_PROCESSES) return IdeRunningProcess.UNAVAILABLE
        val user = System.getProperty("user.name")
        val matches = observed.map { matchLinuxIdeProcess(launch, user, it) }
        when {
            LinuxIdeProcessMatch.SELECTED in matches -> IdeRunningProcess.PRESENT
            LinuxIdeProcessMatch.UNAVAILABLE in matches -> IdeRunningProcess.UNAVAILABLE
            else -> IdeRunningProcess.ABSENT
        }
    } catch (_: SecurityException) {
        IdeRunningProcess.UNAVAILABLE
    }
}

private const val DISCOVERY_SECONDS = 5L
private const val MAX_RUNNING_REPLY = 32
