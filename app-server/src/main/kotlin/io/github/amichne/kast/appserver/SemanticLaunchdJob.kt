package io.github.amichne.kast.appserver

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Compares the live supervisor's job target and executable, not the label alone. */
internal fun ownsSemanticLaunchdJob(raw: String, target: String, command: SemanticServiceLaunch): Boolean {
    val lines = raw.lineSequence().toList()
    if (lines.firstOrNull() != "$target = {") return false
    val arguments = lines.dropWhile { it != "\targuments = {" }.drop(1).takeWhile { it.startsWith("\t\t") }
    val ownedArguments =
        arguments.take(2) == listOf("\t\t/usr/bin/env", "\t\t-i") &&
            "\t\tBROKER_READINESS_FILE=${command.readiness}" in arguments &&
            "\t\tBROKER_SERVICE_IDENTITY=${semanticServiceIdentity(command.installation)}" in arguments &&
            arguments.takeLast(4) == command.arguments.takeLast(4).map { "\t\t$it" }
    return ownedArguments &&
        lines.filter { it.startsWith("\tpath = ") } ==
            listOf("\tpath = ${command.state.resolve("semantic-service.plist")}") &&
        lines.filter { it.startsWith("\tprogram = ") } == listOf("\tprogram = /usr/bin/env") &&
        lines.filter { it.startsWith("\tworking directory = ") } ==
            listOf("\tworking directory = ${command.installation}")
}

internal fun withdrawSemanticLaunchdJob(command: SemanticServiceLaunch): Boolean {
    val absent = LaunchctlExitContract.CompletionOrAbsent(SEMANTIC_LAUNCHD_NOT_FOUND)
    val observation = invokeServiceSupervisor(listOf("/bin/launchctl", "list", command.label), absent)
    if (observation == LaunchctlInvocation.Absent) return true
    if (observation != LaunchctlInvocation.Completed) return false
    val uid = java.nio.file.Files.getAttribute(command.home, "unix:uid") as? Number ?: return false
    val target = "gui/${uid.toLong()}/${command.label}"
    val job = readSemanticLaunchdJob(target) ?: return false
    if (!ownsSemanticLaunchdJob(job, target, command)) return false
    val retired =
        invokeServiceSupervisor(listOf("/bin/launchctl", "bootout", target), LaunchctlExitContract.CompletionOnly)
    if (retired != LaunchctlInvocation.Completed) return false
    return try {
        awaitSupervisorAbsence(
            { invokeServiceSupervisor(listOf("/bin/launchctl", "list", command.label), absent) },
            BrokerOperationalLimits.serviceRetirement.value / BrokerOperationalLimits.servicePoll.value,
        ) {
            Thread.sleep(BrokerOperationalLimits.servicePoll.value)
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

/**
 * A completed bootout signals the job; launchd keeps listing it until the process exits, which for a draining
 * coordinator takes measurable time. Only an observed absence within [observations] proves withdrawal.
 */
internal fun awaitSupervisorAbsence(
    observe: () -> LaunchctlInvocation,
    observations: Long,
    pause: () -> Unit,
): Boolean {
    repeat(observations.toInt().coerceAtLeast(1)) { index ->
        when (observe()) {
            LaunchctlInvocation.Absent -> return true
            LaunchctlInvocation.Completed -> if (index + 1 < observations) pause()
            else -> return false
        }
    }
    return false
}

internal fun readSemanticLaunchdJob(target: String): String? {
    val process =
        try {
            ProcessBuilder("/bin/launchctl", "print", target).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
    return try {
        if (!process.waitFor(BrokerOperationalLimits.launchctlInvocation.value, TimeUnit.MILLISECONDS)) return null
        if (process.exitValue() != 0) return null
        val output = process.inputStream.use { it.readNBytes(MAXIMUM_JOB_BYTES + 1) }
        if (output.size > MAXIMUM_JOB_BYTES) null else output.toString(Charsets.UTF_8)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: IOException) {
        null
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

private const val SEMANTIC_LAUNCHD_NOT_FOUND = 113
private const val MAXIMUM_JOB_BYTES = 32_768
