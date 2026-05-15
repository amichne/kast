package io.github.amichne.kast.cli

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
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
    val failure: String? = null,
    val backendNotStarted: Boolean = false,
) {
    fun toErrorMessage(): String = buildString {
        if (failure == null) {
            appendLine("Timed out waiting for standalone backend at $workspace")
        } else {
            appendLine("Failed before standalone backend readiness polling at $workspace")
            appendLine("failure=$failure")
        }
        appendLine("timeoutMillis=$timeoutMillis")
        appendLine("startupCommand=$commandSummary")
        appendLine(runtimeLibsSummary)
        val exitCode = when {
            processExitCode != null -> processExitCode.toString()
            backendNotStarted -> "<not-started>"
            else -> "<still-running>"
        }
        appendLine("backendExitCode=$exitCode")
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
    javaExecutable: String = "java",
    statusProbe: () -> BackendStatusProbeSnapshot,
    isReady: (BackendStatusProbeSnapshot) -> Boolean,
): Process {
    Files.createDirectories(workspace)
    val runtimeLibs = System.getProperty("kast.runtime-libs")
        ?: failBeforeReadinessPolling(
            workspace = workspace,
            timeoutMillis = timeoutMillis,
            commandSummary = "<not-built>",
            runtimeLibsSummary = runtimeLibsSummary(null, null, emptyList()),
            failure = "kast.runtime-libs system property is missing",
        )
    val classpathFile = Path.of(runtimeLibs).resolve("classpath.txt")
    if (!Files.isRegularFile(classpathFile) || !Files.isReadable(classpathFile)) {
        failBeforeReadinessPolling(
            workspace = workspace,
            timeoutMillis = timeoutMillis,
            commandSummary = "<not-built>",
            runtimeLibsSummary = runtimeLibsSummary(runtimeLibs, classpathFile, emptyList()),
            failure = "runtime classpath file is missing or unreadable: $classpathFile",
        )
    }
    val classpathEntries = classpathFile.toFile().readLines()
        .filter { entry -> entry.isNotBlank() }
    if (classpathEntries.isEmpty()) {
        failBeforeReadinessPolling(
            workspace = workspace,
            timeoutMillis = timeoutMillis,
            commandSummary = "<not-built>",
            runtimeLibsSummary = runtimeLibsSummary(runtimeLibs, classpathFile, classpathEntries),
            failure = "runtime classpath file has no entries: $classpathFile",
        )
    }
    val classpath = classpathEntries.joinToString(System.getProperty("path.separator")) { entry ->
        Path.of(runtimeLibs).resolve(entry).toString()
    }
    val command = buildList {
        add(javaExecutable)
        add("-cp")
        add(classpath)
        add("io.github.amichne.kast.standalone.StandaloneMainKt")
        add("--workspace-root=$workspace")
        addAll(extraArgs)
    }
    val commandSummary = commandSummary(javaExecutable, workspace, runtimeLibs, classpathEntries.size, extraArgs)
    val process = try {
        ProcessBuilder(command)
            .directory(workspace.toFile())
            .also { pb -> env.forEach { (key, value) -> pb.environment()[key] = value } }
            .start()
    } catch (error: IOException) {
        failBeforeReadinessPolling(
            workspace = workspace,
            timeoutMillis = timeoutMillis,
            commandSummary = commandSummary,
            runtimeLibsSummary = runtimeLibsSummary(runtimeLibs, classpathFile, classpathEntries),
            failure = error.stackTraceToString(),
        )
    }
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
            commandSummary = commandSummary,
            runtimeLibsSummary = runtimeLibsSummary(runtimeLibs, classpathFile, classpathEntries),
            processExitCode = processExitCode,
            backendStdout = stdoutCapture.awaitText(),
            backendStderr = stderrCapture.awaitText(),
            lastStatusProbe = lastStatusProbe,
        ).toErrorMessage(),
    )
}

private fun commandSummary(
    javaExecutable: String,
    workspace: Path,
    runtimeLibs: String,
    classpathEntryCount: Int,
    extraArgs: List<String>,
): String = buildList {
    add(javaExecutable)
    add("-cp")
    add("<$classpathEntryCount runtime libs from $runtimeLibs>")
    add("io.github.amichne.kast.standalone.StandaloneMainKt")
    add("--workspace-root=$workspace")
    addAll(extraArgs)
}.joinToString(" ")

private fun failBeforeReadinessPolling(
    workspace: Path,
    timeoutMillis: Long,
    commandSummary: String,
    runtimeLibsSummary: String,
    failure: String,
): Nothing = error(
    BackendReadinessFailureDiagnostics(
        workspace = workspace,
        timeoutMillis = timeoutMillis,
        commandSummary = commandSummary,
        runtimeLibsSummary = runtimeLibsSummary,
        processExitCode = null,
        backendStdout = "",
        backendStderr = "",
        lastStatusProbe = null,
        failure = failure,
        backendNotStarted = true,
    ).toErrorMessage(),
)

private fun runtimeLibsSummary(
    runtimeLibs: String?,
    classpathFile: Path?,
    classpathEntries: List<String>,
): String {
    val preview = classpathEntries.take(5).joinToString(", ")
    val classpathExists = classpathFile?.let(Files::isRegularFile) ?: false
    return "runtimeLibs=${runtimeLibs ?: "<missing>"}; classpathFile=${classpathFile ?: "<not-built>"}; classpath.txt exists=$classpathExists; " +
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
