package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.distribution.managed.SelectedIdeLayout

internal interface IdeLaunchPlatform {
    fun display(): String?

    fun start(arguments: List<String>): Process

    fun processes(): List<IdeProcessObservation>

    data object Native : IdeLaunchPlatform {
        override fun display(): String? = System.getenv("DISPLAY")

        override fun start(arguments: List<String>): Process =
            ProcessBuilder(arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

        override fun processes(): List<IdeProcessObservation> = observeIdeProcesses()
    }
}

internal const val MAX_RUNNING_PROCESSES = 8_192L

internal fun observeIdeProcesses(): List<IdeProcessObservation> =
    ProcessHandle.allProcesses().use { processes ->
        processes
            .limit(MAX_RUNNING_PROCESSES + 1)
            .map { process ->
                val info = process.info()
                IdeProcessObservation(
                    info.user().orElse(null),
                    info.command().orElse(null),
                    info.arguments().orElse(null)?.toList(),
                )
            }
            .toList()
    }

internal data class IdeProcessObservation(val user: String?, val command: String?, val arguments: List<String>?)

internal enum class LinuxIdeProcessMatch {
    SELECTED,
    OTHER,
    UNAVAILABLE,
}

internal fun matchLinuxIdeProcess(
    target: SelectedIdeLaunch.Resolved,
    user: String,
    process: IdeProcessObservation,
): LinuxIdeProcessMatch {
    val java = target.bundledJavaHome.resolve("bin/java").toString()
    if (process.user != null && process.user != user) return LinuxIdeProcessMatch.OTHER
    if (process.command != null && process.command != target.executable && process.command != java)
        return LinuxIdeProcessMatch.OTHER
    if (process.user == null || process.command == null) return LinuxIdeProcessMatch.UNAVAILABLE
    if (process.command == target.executable) return LinuxIdeProcessMatch.SELECTED
    val arguments = process.arguments ?: return LinuxIdeProcessMatch.UNAVAILABLE
    return if ("-Didea.home.path=${target.home}" in arguments) LinuxIdeProcessMatch.SELECTED
    else LinuxIdeProcessMatch.OTHER
}

/** Arguments are derived only from an admitted vendor target; no shell interpolation is involved. */
internal fun ideLaunchArguments(target: SelectedIdeLaunch.Resolved): List<String> =
    when (target.layout) {
        SelectedIdeLayout.MACOS_BUNDLE ->
            listOf("/usr/bin/open", "-g", "-a", target.bundle, "--args", "nosplash", "dontReopenProjects")
        SelectedIdeLayout.LINUX_DIRECTORY -> listOf(target.executable, "nosplash", "dontReopenProjects")
    }
