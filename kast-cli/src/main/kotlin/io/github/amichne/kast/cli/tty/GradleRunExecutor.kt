package io.github.amichne.kast.cli.tty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
internal data class GradleRunResult(
    val ok: Boolean,
    val task: String,
    @SerialName("exit_code")
    val exitCode: Int,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("log_file")
    val logFile: String,
    @SerialName("tasks_executed")
    val tasksExecuted: Int,
    @SerialName("tasks_up_to_date")
    val tasksUpToDate: Int,
    @SerialName("tasks_from_cache")
    val tasksFromCache: Int,
    @SerialName("build_successful")
    val buildSuccessful: Boolean,
    @SerialName("test_task_detected")
    val testTaskDetected: Boolean,
    @SerialName("failure_summary")
    val failureSummary: String? = null,
)

internal class GradleRunExecutor {
    fun run(
        workspaceRoot: Path,
        task: String,
        extraArgs: List<String> = emptyList(),
    ): GradleRunResult {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        val logsDir = normalizedRoot.resolve(".agent-workflow/logs")
        Files.createDirectories(logsDir)
        val logFile = logsDir.resolve("${safeTaskName(task)}-${Instant.now().epochSecond}.log")
        val startMs = System.currentTimeMillis()

        val exitCode = try {
            val command = gradleCommand(normalizedRoot) + task + extraArgs + "--console=plain"
            ProcessBuilder(command)
                .directory(normalizedRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start()
                .waitFor()
        } catch (error: IOException) {
            Files.writeString(logFile, error.message ?: error.toString(), StandardCharsets.UTF_8)
            -1
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Files.writeString(logFile, error.message ?: error.toString(), StandardCharsets.UTF_8)
            -1
        }
        val durationMs = System.currentTimeMillis() - startMs
        val logText = Files.readString(logFile)

        return GradleRunResult(
            ok = exitCode == 0,
            task = task,
            exitCode = exitCode,
            durationMs = durationMs,
            logFile = logFile.toString(),
            tasksExecuted = taskLineRegex.findAll(logText).count(),
            tasksUpToDate = upToDateRegex.findAll(logText).count(),
            tasksFromCache = fromCacheRegex.findAll(logText).count(),
            buildSuccessful = logText.contains("BUILD SUCCESSFUL"),
            testTaskDetected = testTaskRegex.containsMatchIn(task),
            failureSummary = if (exitCode == 0) null else failureSummary(exitCode, logText),
        )
    }

    private fun gradleCommand(workspaceRoot: Path): List<String> {
        val wrapper = workspaceRoot.resolve("gradlew")
        return if (Files.isRegularFile(wrapper)) {
            listOf(wrapper.toString())
        } else {
            listOf("gradle")
        }
    }

    private fun safeTaskName(task: String): String =
        task.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "gradle" }

    private fun failureSummary(
        exitCode: Int,
        logText: String,
    ): String {
        val lines = logText.lineSequence().toList()
        val failureStart = lines.indexOfFirst { it.startsWith("FAILURE:") }
        if (failureStart >= 0) {
            val failureEnd = lines.indexOfFirst { it.startsWith("BUILD FAILED") }
                .takeIf { it >= failureStart }
                ?: (failureStart + 15).coerceAtMost(lines.lastIndex)
            return lines.subList(failureStart, failureEnd + 1)
                .joinToString(" ")
                .take(500)
        }

        val tail = lines.filter(String::isNotBlank).takeLast(10).joinToString(" ").take(500)
        return "Gradle exit code $exitCode. Tail: $tail"
    }

    private companion object {
        val taskLineRegex = Regex("^> Task ", RegexOption.MULTILINE)
        val upToDateRegex = Regex("UP-TO-DATE$", RegexOption.MULTILINE)
        val fromCacheRegex = Regex("FROM-CACHE$", RegexOption.MULTILINE)
        val testTaskRegex = Regex("test|check", RegexOption.IGNORE_CASE)
    }
}
