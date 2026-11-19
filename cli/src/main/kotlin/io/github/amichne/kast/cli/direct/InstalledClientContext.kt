package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.cli.admitNativeControlRoot
import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.installationManifestJson
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.SerializationException

/**
 * Revision-bound installation identity without a payload digest. It selects the installation, observes its fences, and
 * records invocations. Local workspace authority needs [InstalledClientContext.verify]; a coordinator peer serving this
 * exact payload revision carries that proof instead.
 */
internal class InstalledClientBinding
private constructor(
    val installation: Path,
    val payload: Path,
    val environment: Map<String, String>,
    internal val expectedDigest: String,
    private val payloadRevision: ClientFileRevision,
    private val manifest: ClientFileRevision,
) {
    fun observe(): InstalledToolAdmission =
        try {
            if (
                !payloadRevision.isCurrent() ||
                    !manifest.isCurrent() ||
                    !Files.notExists(installation.resolve(".recovery-detached"), NOFOLLOW_LINKS)
            )
                InstalledToolAdmission.UNPROVEN
            else observeInstalledToolAdmission(installation)
        } catch (_: IOException) {
            InstalledToolAdmission.UNPROVEN
        } catch (_: SecurityException) {
            InstalledToolAdmission.UNPROVEN
        }

    companion object {
        fun current(environment: Map<String, String>): Refinement<InstalledClientBinding, ClientInstallationFailure> =
            try {
                val payload =
                    InstalledClientContext::class.java.protectionDomain.codeSource?.location?.toURI()?.let(Path::of)
                if (payload == null) Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                else locate(payload, environment)
            } catch (_: URISyntaxException) {
                Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            } catch (_: SecurityException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            }

        fun locate(
            payload: Path,
            environment: Map<String, String>,
        ): Refinement<InstalledClientBinding, ClientInstallationFailure> =
            try {
                locateFiles(payload, environment)
            } catch (_: IOException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            } catch (_: SecurityException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            } catch (_: SerializationException) {
                Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            }

        private fun locateFiles(
            payload: Path,
            environment: Map<String, String>,
        ): Refinement<InstalledClientBinding, ClientInstallationFailure> {
            if (!payload.isAbsolute || payload.normalize() != payload || payload.toRealPath() != payload)
                return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            val installation =
                when (val selected = clientInstallation(payload)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            if (!Files.notExists(installation.resolve(".recovery-detached"), NOFOLLOW_LINKS))
                return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            val manifest =
                when (val admitted = admitManifest(installation)) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> admitted.value
                }
            val relative = installation.relativize(payload).toString()
            val selected =
                manifest.document.payloadFiles.singleOrNull { it.path == relative }
                    ?: return Refinement.Rejected(ClientInstallationFailure.MANIFEST)
            val revision =
                ClientFileRevision.capture(payload) ?: return Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            val selectedEnvironment =
                if ("KAST_CONFIGURATION_FILE" in environment) environment.toMap()
                else environment + ("KAST_CONFIGURATION_FILE" to manifest.document.configuration)
            val binding =
                InstalledClientBinding(
                    installation,
                    payload,
                    selectedEnvironment,
                    selected.sha256,
                    revision,
                    manifest.revision,
                )
            return when (binding.observe()) {
                InstalledToolAdmission.AVAILABLE,
                InstalledToolAdmission.STOPPED -> Refinement.Refined(binding)
                InstalledToolAdmission.UNPROVEN -> Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
            }
        }

        private fun clientInstallation(payload: Path): Refinement<Path, ClientInstallationFailure> =
            when (payload.parent.fileName.toString()) {
                "bin" ->
                    if (payload.fileName.toString() == "kast-query") Refinement.Refined(payload.parent.parent)
                    else Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                "lib" ->
                    if (payload.fileName.toString().endsWith(".jar")) Refinement.Refined(payload.parent.parent)
                    else Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                else ->
                    when (val native = admitNativeControlRoot(payload)) {
                        is Refinement.Refined -> native
                        is Refinement.Rejected -> Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                    }
            }

        private fun admitManifest(installation: Path): Refinement<AdmittedClientManifest, ClientInstallationFailure> {
            val manifestPath = installation.resolve("installation.json")
            val revision =
                try {
                    ClientFileRevision.capture(manifestPath)
                } catch (_: NoSuchFileException) {
                    null
                } ?: return Refinement.Rejected(ClientInstallationFailure.MANIFEST)
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
            else Refinement.Refined(AdmittedClientManifest(manifest, revision))
        }
    }
}

/** Digest-verified installation evidence shared by local workspace execution and its invocation record. */
internal class InstalledClientContext private constructor(val binding: InstalledClientBinding) {
    val installation: Path
        get() = binding.installation

    val environment: Map<String, String>
        get() = binding.environment

    fun observe(): InstalledToolAdmission = binding.observe()

    companion object {
        fun current(environment: Map<String, String>): Refinement<InstalledClientContext, ClientInstallationFailure> =
            when (val located = InstalledClientBinding.current(environment)) {
                is Refinement.Rejected -> located
                is Refinement.Refined -> verify(located.value)
            }

        fun admit(
            payload: Path,
            environment: Map<String, String>,
        ): Refinement<InstalledClientContext, ClientInstallationFailure> =
            when (val located = InstalledClientBinding.locate(payload, environment)) {
                is Refinement.Rejected -> located
                is Refinement.Refined -> verify(located.value)
            }

        /** Hashes the complete bound payload; the captured revision must still hold afterward. */
        fun verify(binding: InstalledClientBinding): Refinement<InstalledClientContext, ClientInstallationFailure> =
            try {
                val started = System.nanoTime()
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                Files.newInputStream(binding.payload, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(8192)
                    var count = input.read(buffer)
                    while (count >= 0) {
                        digest.update(buffer, 0, count)
                        bytes += count
                        count = input.read(buffer)
                    }
                }
                ControlStageTrace.record(
                    ControlStage.PAYLOAD_VERIFICATION,
                    ControlStageOutcome.COMPLETED,
                    started,
                    bytes,
                )
                if (binding.expectedDigest != "sha256:" + digest.digest().joinToString("") { "%02x".format(it) })
                    Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                else
                    when (binding.observe()) {
                        InstalledToolAdmission.AVAILABLE,
                        InstalledToolAdmission.STOPPED -> Refinement.Refined(InstalledClientContext(binding))
                        InstalledToolAdmission.UNPROVEN -> Refinement.Rejected(ClientInstallationFailure.PAYLOAD)
                    }
            } catch (_: IOException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            } catch (_: SecurityException) {
                Refinement.Rejected(ClientInstallationFailure.IO)
            }
    }
}

private data class AdmittedClientManifest(val document: InstallationManifest, val revision: ClientFileRevision)

private class ClientFileRevision
private constructor(private val path: Path, private val identity: BasicFileAttributes) {
    fun isCurrent(): Boolean {
        val current = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return current.isRegularFile &&
            path.toRealPath() == path &&
            current.fileKey() == identity.fileKey() &&
            current.size() == identity.size() &&
            current.lastModifiedTime() == identity.lastModifiedTime()
    }

    companion object {
        fun capture(path: Path): ClientFileRevision? {
            val identity = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return if (identity.isRegularFile && identity.fileKey() != null) ClientFileRevision(path, identity)
            else null
        }
    }
}

@kotlinx.serialization.Serializable
internal enum class ClientInstallationFailure {
    PAYLOAD,
    MANIFEST,
    IO,
}
