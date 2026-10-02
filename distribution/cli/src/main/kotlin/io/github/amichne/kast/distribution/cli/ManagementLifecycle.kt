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
internal enum class InstallStatus {
    @SerialName("installed") INSTALLED,
    @SerialName("installed-activation-pending") INSTALLED_ACTIVATION_PENDING,
}

@Serializable
internal enum class ActivationType {
    @SerialName("ready") READY,
    @SerialName("pending") PENDING,
    @SerialName("not-requested") NOT_REQUESTED,
}

@Serializable internal data class ActivationReport(val type: ActivationType, val reason: String? = null)

@Serializable
internal data class InstallerReport(
    val operation: String,
    val status: InstallStatus,
    val activation: ActivationReport,
    val semanticVersion: String,
)

private val installerReportJson = Json { ignoreUnknownKeys = true }

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

@Suppress("ThrowsCount")
private fun installedPrivateInstaller(root: Path): Path {
    val selected = selectedInstallation(root)
    val script = selected.resolve("share/kast/install.sh")
    if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS))
        throw ManagementRejected("installation-admission", "private installer is unavailable")
    return script
}

private fun requireOwnedInstaller(root: Path) {
    val installation = selectedInstallation(root)
    val read = readBundledManifest(installation)
    if (read !is BundledManifestRead.Read || !payloadOwned(installation, read.manifest, "share/kast/install.sh"))
        throw ManagementRejected("installation-admission", "private installer ownership is unproven")
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
internal fun installLatest(
    root: Path,
    prior: ManagementReceipt,
    selection: InstallationSelection = InstallationSelection.Latest,
    executor: InstallerExecutor = InstallerExecutor(::executePrivateInstaller),
): InstallerReport {
    val script = installedPrivateInstaller(root)
    if (selection is InstallationSelection.Exact) requireOwnedInstaller(root)
    val options = installerArguments(selection, prior.channel)
    val reportPath =
        try {
            Files.createTempFile(root, ".management-result-", ".json")
        } catch (_: Exception) {
            throw ManagementRejected("upgrade-report", "report destination is unavailable")
        }
    val (code, rawReport) =
        try {
            val exit = executor.execute(script, options, reportPath)
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
    val admitted = admitInstallerReport(rawReport, root, prior.executable, installerReportIntent(selection))
    return when (admitted) {
        is InstallerReportAdmission.Admitted -> admitted.report
        InstallerReportAdmission.Rejected ->
            throw ManagementRejected("upgrade-report", "installed state is unverified after installer success")
    }
}

internal fun installerArguments(selection: InstallationSelection, channel: ReleaseChannel): List<String> =
    when (selection) {
        is InstallationSelection.Exact ->
            listOf("--version", selection.version.value, "--force", "--skip-codex-mcp", "--stage-only")
        InstallationSelection.Latest -> latestInstallerOptions(channel)
        InstallationSelection.ControlOnly -> latestInstallerOptions(channel) + "--control-only"
    }

private fun installerReportIntent(selection: InstallationSelection): InstallerReportIntent =
    when (selection) {
        is InstallationSelection.Exact -> InstallerReportIntent.STAGING
        InstallationSelection.Latest,
        InstallationSelection.ControlOnly -> InstallerReportIntent.ACTIVATION
    }

private fun latestInstallerOptions(channel: ReleaseChannel): List<String> =
    if (channel == ReleaseChannel.DEVELOPER) listOf("--developer-latest", "--skip-codex-mcp")
    else listOf("--skip-codex-mcp")

internal sealed interface InstallerReportAdmission {
    data class Admitted(val report: InstallerReport) : InstallerReportAdmission

    data object Rejected : InstallerReportAdmission
}

internal enum class InstallerReportIntent {
    ACTIVATION,
    STAGING,
}

internal fun admitInstallerReport(
    raw: String?,
    root: Path,
    command: String,
    intent: InstallerReportIntent = InstallerReportIntent.ACTIVATION,
): InstallerReportAdmission {
    if (raw == null) return InstallerReportAdmission.Rejected
    val report =
        try {
            installerReportJson.decodeFromString<InstallerReport>(raw)
        } catch (_: SerializationException) {
            return InstallerReportAdmission.Rejected
        }
    return if (reportMatchesSelectedInstallation(report, root, command, intent))
        InstallerReportAdmission.Admitted(report)
    else InstallerReportAdmission.Rejected
}

private fun reportMatchesSelectedInstallation(
    report: InstallerReport,
    root: Path,
    command: String,
    intent: InstallerReportIntent,
): Boolean =
    report.operation == "installation.install" &&
        report.semanticVersion == readStatus(root, command).installedVersion.value &&
        when (report.status) {
            InstallStatus.INSTALLED ->
                when (intent) {
                    InstallerReportIntent.ACTIVATION -> report.activation.type == ActivationType.READY
                    InstallerReportIntent.STAGING -> report.activation.type == ActivationType.NOT_REQUESTED
                }
            InstallStatus.INSTALLED_ACTIVATION_PENDING ->
                intent == InstallerReportIntent.ACTIVATION && report.activation.type == ActivationType.PENDING
        }

internal fun upgradeInstallation(root: Path, home: Path, controlOnly: Boolean = false) {
    val prior = requireOwnedExecutable(root)
    val selection = if (controlOnly) InstallationSelection.ControlOnly else InstallationSelection.Latest
    val report = withRegistrationLock(root) { installLatest(root, prior, selection) }
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
                registrationRepairAdvice(controlOnly),
        )
    }
    if (report.status == InstallStatus.INSTALLED_ACTIVATION_PENDING)
        throw ManagementRejected(
            "service-activation",
            "installed ${report.semanticVersion}; service activation pending: " +
                "${report.activation.reason ?: "unavailable"}; sessions were interrupted; " +
                serviceRecoveryAdvice(controlOnly),
        )
    val status = readStatus(root, prior.executable)
    println("Installed Kast ${status.installedVersion.value ?: "unavailable"}")
    println(
        if (controlOnly) "Control sessions were interrupted. Running IntelliJ hosts were admitted and reused."
        else
            "Existing calls and sessions were interrupted. " +
                "Restart IntelliJ IDEA and connected harnesses to load the new payload."
    )
}

private fun registrationRepairAdvice(controlOnly: Boolean): String =
    if (controlOnly) "restart the affected harnesses after repair"
    else "restart IntelliJ IDEA and the affected harnesses after repair"

private fun serviceRecoveryAdvice(controlOnly: Boolean): String =
    if (controlOnly) "restart connected harnesses after service recovery"
    else "restart IntelliJ IDEA and connected harnesses after service recovery"

@Suppress("ThrowsCount")
internal fun uninstallInstallation(root: Path, home: Path) {
    val receipt = requireOwnedExecutable(root)
    val script = installedPrivateInstaller(root)
    // The private installer owns verified control retirement and managed control removal.
    val code =
        withRegistrationLock(root) { executePrivateInstaller(script, listOf("uninstall", "--managed-registrations")) }
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
