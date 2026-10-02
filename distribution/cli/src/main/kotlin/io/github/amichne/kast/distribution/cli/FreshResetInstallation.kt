package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_RESET_CAPABILITY
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal sealed interface FreshResetResult {
    data class Prepared(val installation: FreshResetInstallation) : FreshResetResult

    data class Rejected(val failure: ForceResetFailure) : FreshResetResult
}

/** Newly staged, manifest-qualified payload; this type grants no daemon-started or readiness claim. */
internal class FreshResetInstallation
private constructor(
    val installation: Path,
    val version: ReinstallationVersion,
    val command: Path,
    private val manifest: BundledManifest,
) {
    companion object {
        fun admit(root: Path, report: Path): FreshResetResult =
            try {
                admitPayload(root, report)
            } catch (_: IOException) {
                rejected()
            } catch (_: SecurityException) {
                rejected()
            }

        private fun admitPayload(root: Path, report: Path): FreshResetResult {
            val installation = root.resolve("installation")
            val command = root.resolve("bin/kast")
            val read = readBundledManifest(installation)
            if (
                read !is BundledManifestRead.Read ||
                    !Files.isRegularFile(command, NOFOLLOW_LINKS) ||
                    !Files.isExecutable(command)
            )
                return rejected()
            val version = ReinstallationVersion.admit(readStatus(root, command.toString()).installedVersion.value)
            if (version !is ReinstallationVersionAdmission.Admitted) return rejected()
            val payload = FreshResetInstallation(installation, version.version, command, read.manifest)
            if (
                payload.serviceAdmission() != ResetEffect.Completed ||
                    !payloadOwned(installation, read.manifest, "share/kast/libexec/kast-management") ||
                    sha256(command) != sha256(installation.resolve("share/kast/libexec/kast-management"))
            )
                return rejected()
            val admitted =
                admitInstallerReport(
                    readBoundedFile(report, REPORT_BYTES),
                    root,
                    command.toString(),
                    InstallerReportIntent.STAGING,
                )
            return if (admitted is InstallerReportAdmission.Admitted) FreshResetResult.Prepared(payload) else rejected()
        }

        private fun rejected() = FreshResetResult.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED)

        private const val REPORT_BYTES = 65536L
        private const val CAPABILITY_BYTES = 64L
    }

    fun serviceAdmission(): ResetEffect =
        try {
            if (
                payloadOwned(installation, manifest, INSTALLATION_RESET_CAPABILITY) &&
                    readBoundedFile(installation.resolve(INSTALLATION_RESET_CAPABILITY), CAPABILITY_BYTES) == "1\n" &&
                    payloadOwned(installation, manifest, "share/kast/libexec/kast-service")
            )
                ResetEffect.Completed
            else ResetEffect.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED)
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED)
        } catch (_: SecurityException) {
            ResetEffect.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED)
        }
}
