package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.cli.direct.observeInstalledToolAdmission
import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
        fun begin(kind: InvocationRecordKind = InvocationRecordKind.RPC): OneShotInvocationRecord? =
            try {
                val jar = Path.of(KastToolRpcMain::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
                val installation = jar.parent?.takeIf { it.fileName.toString() == "lib" }?.parent ?: return null
                if (observeInstalledToolAdmission(installation) != InstalledToolAdmission.AVAILABLE) return null
                val marker = installation.resolve("share/kast/one-shot-observation-v1")
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.readString(marker) != "1\n")
                    return null
                val start = ProcessHandle.current().info().startInstant().orElse(null) ?: return null
                val directory = installation.resolve(kind.directory)
                Files.createDirectories(directory)
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
                    if (observeInstalledToolAdmission(installation) != InstalledToolAdmission.AVAILABLE) {
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

internal enum class InvocationRecordKind(val directory: String) {
    RPC("state/run/one-shot"),
    MCP_SESSION("state/run/tool-sessions"),
}
