package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private enum class InstallStatus {
    @SerialName("installed") INSTALLED,
    @SerialName("installed-activation-pending") INSTALLED_ACTIVATION_PENDING,
}

@Serializable
private enum class ActivationType {
    @SerialName("ready") READY,
    @SerialName("pending") PENDING,
}

@Serializable private data class ActivationReport(val type: ActivationType, val reason: String? = null)

@Serializable
private data class InstallerReport(
    val operation: String,
    val status: InstallStatus,
    val activation: ActivationReport,
    val semanticVersion: String,
)

private val installerReportJson = Json { ignoreUnknownKeys = true }

private fun requireOwnedExecutable(root: Path): ManagementReceipt {
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

@Suppress("ThrowsCount")
private fun installedPrivateInstaller(root: Path): Path {
    val selected =
        try {
            root.resolve("current").toRealPath()
        } catch (_: Exception) {
            throw ManagementRejected("installation-admission", "selected installation is unavailable")
        }
    if (selected.parent != root.toRealPath().resolve("versions"))
        throw ManagementRejected("installation-admission", "selected installation is invalid")
    val script = selected.resolve("share/kast/install.sh")
    if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS))
        throw ManagementRejected("installation-admission", "private installer is unavailable")
    return script
}

private const val INSTALLER_DEADLINE_SECONDS = 300L

@Suppress("ThrowsCount")
private fun runInstaller(script: Path, arguments: List<String>, report: Path? = null): Int {
    val process =
        try {
            val builder = ProcessBuilder(listOf("/bin/bash", script.toString()) + arguments).inheritIO()
            val environment = builder.environment()
            environment.keys
                .filter {
                    it.startsWith("KAST_INSTALL_") ||
                        it in
                            setOf(
                                "KAST_VERSION",
                                "KAST_RELEASE_BASE_URL",
                                "KAST_MANAGEMENT_CHANNEL",
                                "KAST_MANAGEMENT_REPORT_PATH",
                            )
                }
                .forEach(environment::remove)
            if (report != null) environment["KAST_MANAGEMENT_REPORT_PATH"] = report.toString()
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

private const val INSTALLER_REPORT_LIMIT_BYTES = 65536L

@Suppress("ThrowsCount")
private fun installLatest(root: Path, prior: ManagementReceipt): InstallerReport {
    val script = installedPrivateInstaller(root)
    val options =
        if (prior.channel == ReleaseChannel.DEVELOPER) listOf("--developer-latest", "--skip-codex-mcp")
        else listOf("--skip-codex-mcp")
    val reportPath =
        try {
            Files.createTempFile(root, ".management-result-", ".json")
        } catch (_: Exception) {
            throw ManagementRejected("upgrade-report", "report destination is unavailable")
        }
    val (code, rawReport) =
        try {
            val exit = withRegistrationLock(root) { runInstaller(script, options, reportPath) }
            exit to readBoundedFile(reportPath, INSTALLER_REPORT_LIMIT_BYTES)
        } finally {
            Files.deleteIfExists(reportPath)
        }
    if (code != 0) {
        val status = readStatus(root, prior.executable)
        throw ManagementRejected(
            "upgrade",
            "installer failed with exit $code; installed version: ${status.installedVersion.value ?: "unavailable"}",
        )
    }
    val report =
        try {
            rawReport?.let { installerReportJson.decodeFromString<InstallerReport>(it) }
        } catch (_: SerializationException) {
            null
        } ?: throw ManagementRejected("upgrade-report", "installed state is unverified after installer success")
    if (!reportMatchesSelectedInstallation(report, root, prior.executable))
        throw ManagementRejected("upgrade-report", "installed state does not match the selected release")
    return report
}

private fun reportMatchesSelectedInstallation(report: InstallerReport, root: Path, command: String): Boolean =
    report.operation == "installation.install" &&
        report.semanticVersion == readStatus(root, command).installedVersion.value &&
        when (report.status) {
            InstallStatus.INSTALLED -> report.activation.type == ActivationType.READY
            InstallStatus.INSTALLED_ACTIVATION_PENDING -> report.activation.type == ActivationType.PENDING
        }

internal fun upgradeInstallation(root: Path, home: Path) {
    val prior = requireOwnedExecutable(root)
    val report = installLatest(root, prior)
    val failures = mutableListOf<HarnessConnection>()
    prior.registrations.forEach { registration ->
        try {
            connectHarness(root, home, registration.connection)
        } catch (_: ManagementRejected) {
            failures += registration.connection
        }
    }
    if (failures.isNotEmpty()) {
        val status = readStatus(root, prior.executable)
        throw ManagementRejected(
            "integration-activation",
            "installed ${status.installedVersion.value ?: "unavailable"}; " +
                "registrations require repair: ${failures.joinToString { it.publicName }}; " +
                "restart IntelliJ IDEA and the affected harnesses after repair",
        )
    }
    if (report.status == InstallStatus.INSTALLED_ACTIVATION_PENDING)
        throw ManagementRejected(
            "service-activation",
            "installed ${report.semanticVersion}; service activation pending: " +
                "${report.activation.reason ?: "unavailable"}; sessions were interrupted; " +
                "restart IntelliJ IDEA and connected harnesses after service recovery",
        )
    val status = readStatus(root, prior.executable)
    println("Installed Kast ${status.installedVersion.value ?: "unavailable"}")
    println(
        "Existing calls and sessions were interrupted. " +
            "Restart IntelliJ IDEA and connected harnesses to load the new payload."
    )
}

@Suppress("ThrowsCount")
internal fun uninstallInstallation(root: Path, home: Path) {
    val receipt = requireOwnedExecutable(root)
    val script = installedPrivateInstaller(root)
    // The private installer owns the safe service and plugin shutdown boundary.
    val code = withRegistrationLock(root) { runInstaller(script, listOf("uninstall", "--managed-registrations")) }
    if (code != 0) {
        throw ManagementRejected(
            "uninstall",
            "shutdown or managed removal failed with exit $code; installation remains incomplete",
        )
    }
    val failed = mutableListOf<HarnessConnection>()
    receipt.registrations.forEach { registration ->
        try {
            disconnectConnection(root, home, registration.connection)
        } catch (_: ManagementRejected) {
            failed += registration.connection
        }
    }
    if (failed.isNotEmpty())
        throw ManagementRejected(
            "uninstall",
            "owned registrations require cleanup: ${failed.joinToString { it.publicName }}",
        )
    val executable = Path.of(receipt.executable)
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || sha256(executable) != receipt.executableSha256)
        throw ManagementRejected("uninstall", "public executable identity changed before removal")
    try {
        Files.delete(executable)
        Files.deleteIfExists(receiptPath(root))
        Files.deleteIfExists(root.resolve("management.lock"))
    } catch (_: Exception) {
        throw ManagementRejected("uninstall", "public executable or receipt cleanup failed")
    }
    println("Removed Kast installation and owned registrations")
}
