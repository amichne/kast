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
        val javaExecutable = resolveJavaExecutable()
        val classPath = resolveDetachedClassPath(
            javaClassPath = System.getProperty("java.class.path"),
        )
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

internal fun resolveJavaExecutable(
    javaHomeProperty: String? = System.getProperty("java.home"),
    javaHomeEnvironment: String? = System.getenv("JAVA_HOME"),
): String {
    val executableName = if (isWindows()) "java.exe" else "java"
    javaHomeEnvironment?.takeIf(String::isNotBlank)?.let { javaHome ->
        val javaExecutable = Path.of(javaHome, "bin", executableName)
        if (Files.isExecutable(javaExecutable)) {
            return javaExecutable.toString()
        }
    }
    javaHomeProperty?.takeIf(String::isNotBlank)?.let { javaHome ->
        val javaExecutable = Path.of(javaHome, "bin", executableName)
        if (Files.isExecutable(javaExecutable)) {
            return javaExecutable.toString()
        }
    }
    return executableName
}

internal fun resolveDetachedClassPath(
    javaClassPath: String?,
    environmentRuntimeLibs: Path? = runtimeLibsDirectoryFromEnvironment(),
    currentCommandPath: Path? = currentCommandPath(),
): String {
    environmentRuntimeLibs?.let { runtimeLibsDirectory ->
        return requiredRuntimeLibClassPath(
            runtimeLibsDirectory = runtimeLibsDirectory,
            sourceDescription = "KAST_RUNTIME_LIBS",
        )
    }
    runtimeLibClassPathFromCommandPath(currentCommandPath)?.let { return it }
    return javaClassPath?.takeIf(String::isNotBlank)
        ?: throw CliFailure(
            code = "DAEMON_START_FAILED",
            message = "Could not determine the JVM classpath for the standalone daemon",
        )
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
    val runtimeLibClassPath = singleJarEntry?.let(::runtimeLibClassPathFromSingleJar)

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

private fun runtimeLibsDirectoryFromEnvironment(): Path? = System.getenv("KAST_RUNTIME_LIBS")
    ?.takeIf(String::isNotBlank)
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()

internal fun currentCommandPath(): Path? = ProcessHandle.current().info().command()
    .orElse(null)
    ?.takeIf(String::isNotBlank)
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()

private fun requiredRuntimeLibClassPath(
    runtimeLibsDirectory: Path,
    sourceDescription: String,
): String {
    val normalizedRuntimeLibsDirectory = runtimeLibsDirectory.toAbsolutePath().normalize()
    if (!Files.isDirectory(normalizedRuntimeLibsDirectory)) {
        throw CliFailure(
            code = "DAEMON_START_FAILED",
            message = "$sourceDescription does not point to a runtime-libs directory: $normalizedRuntimeLibsDirectory",
        )
    }
    return runtimeLibClassPath(normalizedRuntimeLibsDirectory)
        ?: throw CliFailure(
            code = "DAEMON_START_FAILED",
            message = "$sourceDescription does not contain a usable runtime-libs/classpath.txt: $normalizedRuntimeLibsDirectory",
        )
}

private fun runtimeLibClassPathFromCommandPath(commandPath: Path?): String? {
    val normalizedCommandPath = commandPath?.toAbsolutePath()?.normalize() ?: return null
    return runtimeLibDirectoryCandidates(normalizedCommandPath)
        .asSequence()
        .mapNotNull(::runtimeLibClassPath)
        .firstOrNull()
}

private fun runtimeLibDirectoryCandidates(commandPath: Path): List<Path> = buildList {
    val commandDirectory = commandPath.parent ?: return emptyList()
    add(commandDirectory.resolve("runtime-libs"))
    add(commandDirectory.resolveSibling("runtime-libs"))
    commandDirectory.parent?.let { parentDirectory ->
        add(parentDirectory.resolve("runtime-libs"))
    }
}.distinct()

private fun runtimeLibClassPathFromSingleJar(singleJarEntry: Path): String? {
    val runtimeLibsDirectory = singleJarEntry.parent?.resolveSibling("runtime-libs")
        ?: return null
    return runtimeLibClassPath(runtimeLibsDirectory)
}

private fun runtimeLibClassPath(runtimeLibsDirectory: Path): String? {
    val normalizedRuntimeLibsDirectory = runtimeLibsDirectory.toAbsolutePath().normalize()
    if (!Files.isDirectory(normalizedRuntimeLibsDirectory)) {
        return null
    }
    val classpathFile = runtimeLibsDirectory.resolve("classpath.txt")
        .takeIf(Files::isRegularFile)
        ?: return null

    return classpathFile.readLines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(normalizedRuntimeLibsDirectory::resolve)
        .filter(Files::isRegularFile)
        .takeIf(List<Path>::isNotEmpty)
        ?.joinToString(File.pathSeparator) { path -> path.toString() }
}

private fun isWindows(): Boolean = System.getProperty("os.name")
    ?.contains("win", ignoreCase = true)
    ?: false
