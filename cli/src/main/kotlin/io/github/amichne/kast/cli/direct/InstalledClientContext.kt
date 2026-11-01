package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.installationManifestJson
import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.SerializationException

/** Runtime-only installation evidence shared by direct execution and its invocation record. */
internal class InstalledClientContext
private constructor(
    val installation: Path,
    val environment: Map<String, String>,
    private val payload: Path,
    private val identity: BasicFileAttributes,
) {
    fun observe(): InstalledToolAdmission =
        try {
            val current = Files.readAttributes(payload, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (
                !payloadUnchanged(current) ||
                    !Files.notExists(installation.resolve(".recovery-detached"), NOFOLLOW_LINKS)
            )
                InstalledToolAdmission.UNPROVEN
            else observeInstalledToolAdmission(installation)
        } catch (_: IOException) {
            InstalledToolAdmission.UNPROVEN
        } catch (_: SecurityException) {
            InstalledToolAdmission.UNPROVEN
        }

    private fun payloadUnchanged(current: BasicFileAttributes): Boolean =
        current.isRegularFile && payload.toRealPath() == payload && sameIdentity(current)

    private fun sameIdentity(current: BasicFileAttributes): Boolean =
        current.fileKey() == identity.fileKey() &&
            current.size() == identity.size() &&
            current.lastModifiedTime() == identity.lastModifiedTime()

    companion object {
        fun current(environment: Map<String, String>): Refinement<InstalledClientContext, ClientInstallationFailure> =
            try {
                val payload =
                    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
                        ProcessHandle.current().info().command().map(Path::of).orElse(null)
                    } else {
                        InstalledClientContext::class.java.protectionDomain.codeSource?.location?.toURI()?.let(Path::of)
                    }
                if (payload == null) Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                else admit(payload, environment)
            } catch (_: URISyntaxException) {
                Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            } catch (_: SecurityException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            }

        fun admit(
            payload: Path,
            environment: Map<String, String>,
        ): Refinement<InstalledClientContext, ClientInstallationFailure> =
            try {
                admitFiles(payload, environment)
            } catch (_: IOException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            } catch (_: SecurityException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            } catch (_: SerializationException) {
                Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            }

        private fun admitFiles(
            payload: Path,
            environment: Map<String, String>,
        ): Refinement<InstalledClientContext, ClientInstallationFailure> {
            if (!payload.isAbsolute || payload.normalize() != payload || payload.toRealPath() != payload)
                return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            val directory = payload.parent
            val installation = directory.parent
            if (!clientPayload(payload) || !Files.notExists(installation.resolve(".recovery-detached"), NOFOLLOW_LINKS))
                return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            return when (val manifest = admitManifest(installation)) {
                is Refinement.Rejected -> manifest
                is Refinement.Refined -> admitPayload(payload, installation, manifest.value, environment)
            }
        }

        private fun clientPayload(payload: Path): Boolean =
            when (payload.parent.fileName.toString()) {
                "bin" -> payload.fileName.toString() == "kast-query"
                "lib" -> payload.fileName.toString().endsWith(".jar")
                else -> false
            }

        private fun admitManifest(installation: Path): Refinement<InstallationManifest, ClientInstallationFailure> {
            val manifestPath = installation.resolve("installation.json")
            if (!Files.isRegularFile(manifestPath, NOFOLLOW_LINKS))
                return Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            val bytes =
                Files.newInputStream(manifestPath, NOFOLLOW_LINKS).use {
                    it.readNBytes(ControlDistributionLimits.maximumManifestBytes + 1)
                }
            if (bytes.size > ControlDistributionLimits.maximumManifestBytes)
                return Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            val manifest =
                installationManifestJson.decodeFromString<InstallationManifest>(bytes.toString(Charsets.UTF_8))
            return if (
                manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
                    manifest.installationRoot != installation.toString() ||
                    manifest.configuration != installation.resolve("config/environment").toString()
            )
                Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            else Refinement.Refined(manifest)
        }

        private fun admitPayload(
            payload: Path,
            installation: Path,
            manifest: InstallationManifest,
            environment: Map<String, String>,
        ): Refinement<InstalledClientContext, ClientInstallationFailure> {
            val relative = installation.relativize(payload).toString()
            val selected = manifest.payloadFiles.singleOrNull { it.path == relative }
            if (selected == null) return Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            val identity = Files.readAttributes(payload, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!identity.isRegularFile || identity.fileKey() == null)
                return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(payload, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            if (selected.sha256 != "sha256:" + digest.digest().joinToString("") { "%02x".format(it) })
                return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            val selectedEnvironment =
                if ("KAST_CONFIGURATION_FILE" in environment) environment.toMap()
                else environment + ("KAST_CONFIGURATION_FILE" to manifest.configuration)
            val context = InstalledClientContext(installation, selectedEnvironment, payload, identity)
            return when (context.observe()) {
                InstalledToolAdmission.AVAILABLE,
                InstalledToolAdmission.STOPPED -> Refinement.Refined(context)
                InstalledToolAdmission.UNPROVEN -> Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            }
        }
    }
}

internal enum class ClientInstallationFailure {
    PAYLOAD,
    MANIFEST,
    IO,
}
