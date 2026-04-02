package io.github.amichne.kast.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

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
            detachedJavaCommand(
                javaExecutable = javaExecutable,
                classPath = classPath,
                mainClassName = mainClassName,
                arguments = arguments,
            ),
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

internal fun detachedJavaCommand(
    javaExecutable: String,
    classPath: String,
    mainClassName: String,
    arguments: List<String>,
): List<String> {
    val classPathEntries = classPath
        .split(File.pathSeparatorChar)
        .map(String::trim)
        .filter(String::isNotEmpty)
    val singleJarEntry = classPathEntries
        .singleOrNull()
        ?.let(Path::of)
        ?.takeIf { path -> path.fileName.toString().endsWith(".jar") && Files.isRegularFile(path) }
    val runtimeLibClassPath = singleJarEntry?.let(::runtimeLibClassPath)

    return buildList {
        add(javaExecutable)
        when {
            runtimeLibClassPath != null -> {
                add("-cp")
                add(runtimeLibClassPath)
                add(mainClassName)
                addAll(arguments)
            }

            singleJarEntry != null -> {
                add("-jar")
                add(singleJarEntry.toString())
                addAll(arguments)
            }

            else -> {
                add("-cp")
                add(classPath)
                add(mainClassName)
                addAll(arguments)
            }
        }
    }
}

private fun runtimeLibClassPath(singleJarEntry: Path): String? {
    val runtimeLibsDirectory = singleJarEntry.parent?.resolveSibling("runtime-libs")
        ?.takeIf(Files::isDirectory)
        ?: return null
    val classpathFile = runtimeLibsDirectory.resolve("classpath.txt")
        .takeIf(Files::isRegularFile)
        ?: return null

    return classpathFile.readLines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(runtimeLibsDirectory::resolve)
        .filter(Files::isRegularFile)
        .takeIf(List<Path>::isNotEmpty)
        ?.joinToString(File.pathSeparator) { path -> path.toString() }
}

private fun isWindows(): Boolean = System.getProperty("os.name")
    ?.contains("win", ignoreCase = true)
    ?: false
