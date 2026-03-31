package io.github.amichne.kast.cli

import java.nio.file.Files
import java.nio.file.Path

internal interface ProcessLauncher {
    fun startDetached(
        mainClassName: String,
        workingDirectory: Path,
        logFile: Path,
        arguments: List<String>,
    ): StartedProcess
}

internal data class StartedProcess(
    val pid: Long,
    val logFile: Path,
)

internal class DefaultProcessLauncher : ProcessLauncher {
    override fun startDetached(
        mainClassName: String,
        workingDirectory: Path,
        logFile: Path,
        arguments: List<String>,
    ): StartedProcess {
        Files.createDirectories(logFile.parent)
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (isWindows()) "java.exe" else "java",
        ).toString()
        val classPath = System.getProperty("java.class.path")
            ?: throw CliFailure(code = "DAEMON_START_FAILED", message = "java.class.path is not available")
        val process = ProcessBuilder(
            buildList {
                add(javaExecutable)
                add("-cp")
                add(classPath)
                add(mainClassName)
                addAll(arguments)
            },
        )
            .directory(workingDirectory.toFile())
            .redirectOutput(logFile.toFile())
            .redirectErrorStream(true)
            .start()
        return StartedProcess(
            pid = process.pid(),
            logFile = logFile,
        )
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name")
    ?.contains("win", ignoreCase = true)
    ?: false
