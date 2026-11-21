package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun requireOwnedExecutable(root: Path): ManagementReceipt {
    val receipt =
        when (val read = readReceipt(root)) {
            is ReceiptRead.Read -> read.receipt
            is ReceiptRead.Unavailable -> throw ManagementRejected("installation-admission", read.reason)
        }
    val path = Path.of(receipt.executable)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || sha256(path) != receipt.executableSha256)
        throw ManagementRejected("installation-admission", "public executable ownership is unproven")
    return receipt
}

private const val INSTALLER_DEADLINE_SECONDS = 300L

@Suppress("ThrowsCount")
internal fun executePrivateInstaller(script: Path, arguments: List<String>, report: Path? = null): Int {
    val process =
        try {
            val builder =
                ProcessBuilder(listOf("/bin/bash", script.toString()) + arguments)
                    .inheritIO()
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("/dev/stderr").toFile()))
            val selectedEnvironment = privateInstallerEnvironment(builder.environment(), report)
            builder.environment().apply {
                clear()
                putAll(selectedEnvironment)
            }
            builder.start()
        } catch (_: Exception) {
            throw ManagementRejected("installer-launch", "private installer could not start")
        }
    return try {
        if (!process.waitFor(INSTALLER_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            throw ManagementRejected("installer-deadline", "installer did not settle within the deadline")
        }
        process.exitValue()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ManagementRejected("installer-interrupted", "installation state must be inspected")
    }
}

private val inheritedInstallerSelectors =
    setOf(
        "KAST_VERSION",
        "KAST_HOST_VERSION",
        "KAST_RELEASE_BASE_URL",
        "KAST_MANAGEMENT_CHANNEL",
        "KAST_MANAGEMENT_REPORT_PATH",
    )

internal fun privateInstallerEnvironment(inherited: Map<String, String>, report: Path?): Map<String, String> {
    val selected = inherited.filterKeys { !it.startsWith("KAST_INSTALL_") && it !in inheritedInstallerSelectors }
    return if (report == null) selected else selected + ("KAST_MANAGEMENT_REPORT_PATH" to report.toString())
}
