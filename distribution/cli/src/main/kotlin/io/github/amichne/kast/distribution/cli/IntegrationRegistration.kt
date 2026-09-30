package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val EXECUTABLE_PERMISSION_MASK = 73

@Serializable private data class CodexServer(val name: String, val transport: CodexTransport)

@Serializable
private data class CodexTransport(val type: String, val command: String = "", val args: List<String> = emptyList())

private val codexJson = Json { ignoreUnknownKeys = true }
private const val CODEX_DEADLINE_SECONDS = 8L
private const val CODEX_OUTPUT_LIMIT_BYTES = 262144

internal sealed interface ProcessObservation {
    data class Exited(val code: Int, val output: String) : ProcessObservation

    data object Unavailable : ProcessObservation
}

internal fun runBounded(arguments: List<String>, codexHome: Path? = null): ProcessObservation {
    val process =
        try {
            ProcessBuilder(arguments)
                .apply {
                    if (codexHome != null) environment()["CODEX_HOME"] = codexHome.toString()
                }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            return ProcessObservation.Unavailable
        }
    return try {
        if (!process.waitFor(CODEX_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ProcessObservation.Unavailable
        } else {
            val output = process.inputStream.readNBytes(CODEX_OUTPUT_LIMIT_BYTES + 1)
            if (output.size > CODEX_OUTPUT_LIMIT_BYTES) ProcessObservation.Unavailable
            else ProcessObservation.Exited(process.exitValue(), output.decodeToString())
        }
    } catch (_: Exception) {
        process.destroyForcibly()
        ProcessObservation.Unavailable
    }
}

@Suppress("ThrowsCount")
private fun codexRegistration(execute: (List<String>) -> ProcessObservation = { runBounded(it) }): CodexServer? {
    val response = execute(listOf("codex", "mcp", "list", "--json"))
    if (response !is ProcessObservation.Exited || response.code != 0)
        throw ManagementRejected("registration-inspection", "Codex configuration is unavailable")
    val entries =
        try {
            codexJson.decodeFromString<List<CodexServer>>(response.output)
        } catch (_: SerializationException) {
            throw ManagementRejected("registration-inspection", "Codex configuration is invalid")
        }
    val matches = entries.filter { it.name == "kast" }
    if (matches.size > 1) throw ManagementRejected("registration-inspection", "Codex registration is ambiguous")
    return matches.singleOrNull()
}

private fun writeStagedPayload(payload: RegistrationPayload, staged: Path) {
    when (payload) {
        is RegistrationPayload.BundledFile -> Files.copy(payload.source, staged, StandardCopyOption.REPLACE_EXISTING)
        is RegistrationPayload.Launcher -> {
            Files.writeString(staged, payload.content)
            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
    if (sha256(staged) != payload.digest) throw ManagementRejected("connect", "staged payload is unverified")
}

@Suppress("ThrowsCount", "ComplexCondition")
internal fun verifiedBundledSource(installation: Path, connection: HarnessConnection): Path {
    val source = registrationSourceFor(installation, connection)
    val manifest =
        when (val read = readBundledManifest(installation)) {
            is BundledManifestRead.Read -> read.manifest
            BundledManifestRead.Unavailable -> throw ManagementRejected("connect", "release manifest is unavailable")
            BundledManifestRead.Invalid -> throw ManagementRejected("connect", "release manifest is invalid")
        }
    val relative = installation.relativize(source).toString().replace('\\', '/')
    val entries = manifest.payloadFiles.filter { it.path == relative }
    if (
        manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
            manifest.installationRoot != installation.toString() ||
            entries.size != 1 ||
            !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
            entries.single().sha256 != "sha256:${sha256(source)}" ||
            (connection == HarnessConnection.CODEX_APP_SERVER &&
                (entries.single().mode and EXECUTABLE_PERMISSION_MASK == 0 || !Files.isExecutable(source)))
    )
        throw ManagementRejected("connect", "release-bundled integration is unverified")
    return source
}

private fun admittedReceipt(root: Path): ManagementReceipt =
    when (val read = readReceipt(root)) {
        is ReceiptRead.Read -> read.receipt
        is ReceiptRead.Unavailable -> throw ManagementRejected("installation-admission", read.reason)
    }

private fun ownsCodex(server: CodexServer?, destination: Path): Boolean =
    server != null &&
        server.transport.type == "stdio" &&
        server.transport.command == destination.toString() &&
        server.transport.args.isEmpty()

@Suppress(
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "LongMethod",
    "ComplexCondition",
    "TooGenericExceptionCaught",
)
internal fun connectHarness(
    root: Path,
    home: Path,
    connection: HarnessConnection,
    ownership: RegistrationOwnership = RegistrationOwnership.REQUIRE_OWNED,
    codexHome: Path = System.getenv("CODEX_HOME")?.let(Path::of) ?: home.resolve(".codex"),
    executeCodex: (List<String>) -> ProcessObservation = { runBounded(it, codexHome) },
    commitReceipt: (Path, ManagementReceipt) -> Unit = ::writeManagementReceipt,
): Boolean =
    withRegistrationLock(root) {
        val receipt = admittedReceipt(root)
        val source = verifiedBundledSource(selectedInstallation(root), connection)
        val payload = registrationPayload(root, source, connection)
        val digest = payload.digest
        val destination = registrationDestinationFor(root, home, connection)
        val prior = receipt.registrations.singleOrNull { it.connection == connection }
        val force = ownership == RegistrationOwnership.REPLACE_SELECTED_SLOT
        val migration =
            if (!force && prior != null && prior.destination != destination.toString()) {
                when (val admitted = LegacyMcpMigration.admit(root, prior)) {
                    is LegacyConnectionAdmission.Admitted -> admitted.migration
                    is LegacyConnectionAdmission.Rejected ->
                        throw ManagementRejected("connect-migration", admitted.failure.name.lowercase())
                }
            } else null
        val selectedFile =
            if (connection == HarnessConnection.CODEX_MCP) codexHome.resolve("config.toml") else destination
        val registrationAnchor = if (connection == HarnessConnection.CODEX_MCP) codexHome else home
        requireRegistrationPath(selectedFile, registrationAnchor)
        val existing = Files.exists(selectedFile, LinkOption.NOFOLLOW_LINKS)
        val current = if (existing) sha256(selectedFile) else null
        val replace =
            if (connection == HarnessConnection.CODEX_MCP) {
                val observed = codexRegistration(executeCodex)
                if (migration != null && !ownsCodex(observed, migration.command))
                    throw ManagementRejected(
                        "connect-migration",
                        LegacyConnectionRejection.LEGACY_REGISTRATION_UNVERIFIED.name.lowercase(),
                    )
                if (!force && migration == null && observed != null && !ownsCodex(observed, destination))
                    throw ManagementRejected("connect", "Codex name belongs to another configuration")
                !ownsCodex(observed, destination)
            } else {
                if (!force && existing && (prior == null || (current != digest && current != prior.payloadSha256)))
                    throw ManagementRejected("connect", "integration file belongs to another owner")
                current != digest || (payload is RegistrationPayload.Launcher && !Files.isExecutable(destination))
            }
        val record = ManagedRegistration(connection, destination.toString(), digest)
        val next =
            receipt.copy(registrations = receipt.registrations.filterNot { it.connection == connection } + record)
        // Capture both preimages before any replacement. Codex's full configuration is opaque:
        // restoring these bytes preserves fields outside the inspection DTO, including credentials.
        val registration = captureRegistrationPreimage(selectedFile, registrationAnchor)
        val receiptPreimage =
            try {
                captureRegistrationPreimage(receiptPath(root), root)
            } catch (failure: Exception) {
                registration.discard()
                throw failure
            }
        var registrationAfter = registrationPostimage(selectedFile)
        try {
            if (replace) {
                if (connection == HarnessConnection.CODEX_MCP) {
                    val add =
                        try {
                            executeCodex(listOf("codex", "mcp", "add", "kast", "--", destination.toString()))
                        } finally {
                            registrationAfter = registrationPostimage(selectedFile)
                        }
                    if (add !is ProcessObservation.Exited || add.code != 0)
                        throw ManagementRejected("connect", "Codex registration replacement failed")
                } else {
                    val staged = Files.createTempFile(destination.parent, ".kast-", ".new")
                    try {
                        writeStagedPayload(payload, staged)
                        Files.move(
                            staged,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        registrationAfter = RegistrationPostimage.Present(digest)
                    } finally {
                        Files.deleteIfExists(staged)
                    }
                }
            }
            if (connection == HarnessConnection.CODEX_MCP) {
                if (!ownsCodex(codexRegistration(executeCodex), destination))
                    throw ManagementRejected("connect", "Codex registration was not verified")
            } else if (sha256(destination) != digest) {
                throw ManagementRejected("connect", "registration verification failed")
            }
            commitReceipt(root, next)
            if (readReceipt(root) != ReceiptRead.Read(next))
                throw ManagementRejected("connect", "ownership receipt was not verified")
        } catch (failure: Exception) {
            // Attempt both restorations even if one fails. A failed restoration retains its
            // original backup; never delete the only recovery copy in a finally block.
            val recovery =
                listOf(
                        registration.restore(registrationAfter),
                        receiptPreimage.restore(registrationPostimage(receiptPath(root))),
                    )
                    .filterIsInstance<RegistrationRecovery.Required>()
            if (recovery.isNotEmpty())
                throw ManagementRejected("connect-recovery", recovery.joinToString("; ") { it.description })
            if (failure is ManagementRejected) throw failure
            throw ManagementRejected("connect", "ownership transaction failed; exact preimages restored")
        }
        registration.discard()
        receiptPreimage.discard()
        prior == record && !replace
    }

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun disconnectHarness(root: Path, home: Path, harness: Harness): Boolean =
    HarnessConnection.entries.filter { it.harness == harness }.map { disconnectConnection(root, home, it) }.any { it }

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun disconnectConnection(root: Path, home: Path, connection: HarnessConnection): Boolean =
    withRegistrationLock(root) {
        val receipt = admittedReceipt(root)
        val prior =
            receipt.registrations.singleOrNull { it.connection == connection } ?: return@withRegistrationLock false
        val destination = registrationDestinationFor(root, home, connection)
        if (prior.destination != destination.toString())
            throw ManagementRejected("disconnect", "recorded registration identity changed")
        if (connection == HarnessConnection.CODEX_MCP) {
            val observed = codexRegistration()
            if (observed != null && !ownsCodex(observed, destination))
                throw ManagementRejected("disconnect", "Codex name belongs to another configuration")
            if (observed != null) {
                val remove = runBounded(listOf("codex", "mcp", "remove", "kast"))
                if (remove !is ProcessObservation.Exited || remove.code != 0 || codexRegistration() != null)
                    throw ManagementRejected("disconnect", "Codex removal was not verified")
            }
        } else {
            requireRegistrationPath(destination, home)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (
                    !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) ||
                        sha256(destination) != prior.payloadSha256
                )
                    throw ManagementRejected("disconnect", "integration file is no longer owned")
                Files.delete(destination)
            }
        }
        writeManagementReceipt(
            root,
            receipt.copy(registrations = receipt.registrations.filterNot { it.connection == connection }),
        )
        true
    }

@Suppress("ThrowsCount")
internal fun <T> withRegistrationLock(root: Path, action: () -> T): T {
    val lock = root.resolve("management.lock")
    if (Files.isSymbolicLink(lock)) throw ManagementRejected("registration-lock", "lock path is untrusted")
    try {
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val acquired =
                channel.tryLock() ?: throw ManagementRejected("registration-lock", "another operation is active")
            acquired.use {
                return action()
            }
        }
    } catch (failure: ManagementRejected) {
        throw failure
    } catch (_: Exception) {
        throw ManagementRejected("registration-lock", "lock is unavailable")
    }
}

private fun RegistrationPreimage.restore(expected: RegistrationPostimage): RegistrationRecovery =
    try {
        requireRegistrationPath(destination, anchor)
        check(expected != RegistrationPostimage.Unavailable && registrationPostimage(destination) == expected)
        when (this) {
            is RegistrationPreimage.Absent -> Files.deleteIfExists(destination)
            is RegistrationPreimage.Present -> {
                val staged = Files.createTempFile(destination.parent, ".kast-", ".restore")
                try {
                    Files.copy(
                        backup,
                        staged,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    Files.setPosixFilePermissions(staged, permissions)
                    Files.move(
                        staged,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    check(sha256(destination) == digest)
                } finally {
                    Files.deleteIfExists(staged)
                }
            }
        }
        discard()
        RegistrationRecovery.Restored
    } catch (_: Exception) {
        RegistrationRecovery.Required(
            when (this) {
                is RegistrationPreimage.Absent -> "remove newly created registration at $destination"
                is RegistrationPreimage.Present -> "restore $destination from preserved backup $backup"
            }
        )
    }

private fun RegistrationPreimage.discard() {
    if (this is RegistrationPreimage.Present) Files.deleteIfExists(backup)
}

@Suppress("TooGenericExceptionCaught") // Cleanup must retain the original failure for every capture effect.
private fun captureRegistrationPreimage(destination: Path, anchor: Path): RegistrationPreimage {
    requireRegistrationPath(destination, anchor)
    Files.createDirectories(destination.parent)
    if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return RegistrationPreimage.Absent(destination, anchor)
    val backup = Files.createTempFile(destination.parent, ".kast-", ".prior")
    try {
        Files.copy(destination, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        // Backups may contain Codex credentials. Keep access restricted even for a permissive original.
        Files.setPosixFilePermissions(
            backup,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        )
        val digest = sha256(destination)
        check(sha256(backup) == digest)
        return RegistrationPreimage.Present(
            destination,
            anchor,
            backup,
            digest,
            Files.getPosixFilePermissions(destination),
        )
    } catch (failure: Exception) {
        Files.deleteIfExists(backup)
        throw failure
    }
}
