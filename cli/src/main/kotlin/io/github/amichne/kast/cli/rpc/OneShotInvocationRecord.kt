package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.cli.direct.InstalledClientBinding
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One active call owns one bounded process-identity witness. */
internal class OneShotInvocationRecord private constructor(private val path: Path, private val fileKey: Any) :
    AutoCloseable {
    override fun close() {
        try {
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (current.isRegularFile && current.fileKey() == fileKey) Files.delete(path)
        } catch (_: Exception) {
            /* Retain uncertain evidence; status rejects it. */
        }
    }

    companion object {
        fun begin(
            context: InstalledClientContext,
            kind: InvocationRecordKind = InvocationRecordKind.RPC,
        ): OneShotInvocationRecord? = begin(context.binding, kind)

        /** Retirement needs only the process and installation identities; no payload digest is required. */
        fun begin(
            binding: InstalledClientBinding,
            kind: InvocationRecordKind = InvocationRecordKind.RPC,
        ): OneShotInvocationRecord? =
            try {
                val installation = binding.installation
                if (binding.observe() != InstalledToolAdmission.AVAILABLE) return null
                val layout = InstallationLayout.of(installation)
                val marker = layout.payload.oneShotObservationCapability
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.readString(marker) != "1\n")
                    return null
                val start = ProcessHandle.current().info().startInstant().orElse(null) ?: return null
                val directory = kind.directory(layout)
                Files.createDirectories(
                    directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                if (directory.toRealPath() != directory) return null
                val temporary = Files.createTempFile(directory, ".kast-", ".tmp")
                try {
                    Files.writeString(
                        temporary,
                        Json.encodeToString(
                            InstalledToolInvocation(
                                1,
                                ProcessHandle.current().pid(),
                                start.toEpochMilli(),
                            )
                        ),
                    )
                    val path = directory.resolve("${UUID.randomUUID()}.json")
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
                    val identity =
                        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val key = identity.fileKey() ?: return null
                    val record = OneShotInvocationRecord(path, key)
                    if (binding.observe() != InstalledToolAdmission.AVAILABLE) {
                        record.close()
                        return null
                    }
                    record
                } finally {
                    Files.deleteIfExists(temporary)
                }
            } catch (_: Exception) {
                null
            }
    }
}

internal enum class InvocationRecordKind {
    RPC,
    MCP_SESSION;

    fun directory(installation: InstallationLayout): Path =
        when (this) {
            RPC -> installation.state.run.oneShotInvocations
            MCP_SESSION -> installation.state.run.toolSessions
        }
}
