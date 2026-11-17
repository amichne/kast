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

private const val MAXIMUM_JOB_BYTES = 32_768
