package io.github.amichne.kast.cli

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class BackendStatusProbeSnapshot(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val failure: String? = null,
)

internal data class BackendReadinessFailureDiagnostics(
    val workspace: Path,
    val timeoutMillis: Long,
    val commandSummary: String,
    val runtimeLibsSummary: String,
    val processExitCode: Int?,
    val backendStdout: String,
    val backendStderr: String,
    val lastStatusProbe: BackendStatusProbeSnapshot?,
) {
    fun toErrorMessage(): String = buildString {
        appendLine("Timed out waiting for standalone backend at $workspace")
        appendLine("timeoutMillis=$timeoutMillis")
        appendLine("startupCommand=$commandSummary")
        appendLine(runtimeLibsSummary)
        appendLine("backendExitCode=${processExitCode ?: "<still-running>"}")
        appendLine("backendStdout=${backendStdout.ifBlank { "<empty>" }}")
        appendLine("backendStderr=${backendStderr.ifBlank { "<empty>" }}")
        if (lastStatusProbe == null) {
            appendLine("lastStatusProbe=<none>")
        } else {
            appendLine("lastStatusProbe.exitCode=${lastStatusProbe.exitCode ?: "<not-started>"}")
            appendLine("lastStatusProbe.stdout=${lastStatusProbe.stdout.ifBlank { "<empty>" }}")
            appendLine("lastStatusProbe.stderr=${lastStatusProbe.stderr.ifBlank { "<empty>" }}")
            lastStatusProbe.failure?.let { failure ->
                appendLine("lastStatusProbe.failure=$failure")
            }
        }
    }.trim()
}

internal fun startStandaloneBackendForTest(
    workspace: Path,
    env: Map<String, String> = emptyMap(),
    extraArgs: List<String> = emptyList(),
    timeoutMillis: Long = 120_000,
    statusProbe: () -> BackendStatusProbeSnapshot,
    isReady: (BackendStatusProbeSnapshot) -> Boolean,
): Process {
    val runtimeLibs = checkNotNull(System.getProperty("kast.runtime-libs")) {
        "kast.runtime-libs system property is missing"
    }
    val classpathFile = Path.of(runtimeLibs).resolve("classpath.txt")
    val classpathEntries = classpathFile.toFile().readLines()
        .filter { entry -> entry.isNotBlank() }
    val classpath = classpathEntries.joinToString(System.getProperty("path.separator")) { entry ->
        Path.of(runtimeLibs).resolve(entry).toString()
    }
    val command = buildList {
        add("java")
        add("-cp")
        add(classpath)
        add("io.github.amichne.kast.standalone.StandaloneMainKt")
        add("--workspace-root=$workspace")
        addAll(extraArgs)
    }
    Files.createDirectories(workspace)
    val process = ProcessBuilder(command)
        .directory(workspace.toFile())
        .also { pb -> env.forEach { (key, value) -> pb.environment()[key] = value } }
        .start()
    val stdoutCapture = StreamCapture(process.inputStream)
    val stderrCapture = StreamCapture(process.errorStream)
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    var lastStatusProbe: BackendStatusProbeSnapshot? = null
    while (System.nanoTime() < deadline && process.isAlive) {
        val probe = runCatching { statusProbe() }.getOrElse { error ->
            BackendStatusProbeSnapshot(failure = error.stackTraceToString())
        }
        lastStatusProbe = probe
        if (isReady(probe)) {
            return process
        }
        Thread.sleep(500)
    }

    if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
    val processExitCode = runCatching { process.exitValue() }.getOrNull()
    error(
        BackendReadinessFailureDiagnostics(
            workspace = workspace,
            timeoutMillis = timeoutMillis,
            commandSummary = commandSummary(workspace, runtimeLibs, classpathEntries.size, extraArgs),
            runtimeLibsSummary = runtimeLibsSummary(runtimeLibs, classpathFile, classpathEntries),
            processExitCode = processExitCode,
            backendStdout = stdoutCapture.awaitText(),
            backendStderr = stderrCapture.awaitText(),
            lastStatusProbe = lastStatusProbe,
        ).toErrorMessage(),
    )
}

private fun commandSummary(
    workspace: Path,
    runtimeLibs: String,
    classpathEntryCount: Int,
    extraArgs: List<String>,
): String = buildList {
    add("java")
    add("-cp")
    add("<$classpathEntryCount runtime libs from $runtimeLibs>")
    add("io.github.amichne.kast.standalone.StandaloneMainKt")
    add("--workspace-root=$workspace")
    addAll(extraArgs)
}.joinToString(" ")

private fun runtimeLibsSummary(
    runtimeLibs: String,
    classpathFile: Path,
    classpathEntries: List<String>,
): String {
    val preview = classpathEntries.take(5).joinToString(", ")
    return "runtimeLibs=$runtimeLibs; classpathFile=$classpathFile; classpath.txt exists=${Files.isRegularFile(classpathFile)}; " +
        "entries=${classpathEntries.size}; firstEntries=${preview.ifBlank { "<empty>" }}"
}

private class StreamCapture(stream: InputStream) {
    private val bytes = ByteArrayOutputStream()
    private val reader = thread(start = true, isDaemon = true, name = "backend-output-capture") {
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                synchronized(bytes) {
                    bytes.write(buffer, 0, read)
                }
            }
        }
    }

    fun awaitText(): String {
        reader.join(TimeUnit.SECONDS.toMillis(5))
        return synchronized(bytes) {
            bytes.toString(Charsets.UTF_8.name()).trim()
        }
    }
}
