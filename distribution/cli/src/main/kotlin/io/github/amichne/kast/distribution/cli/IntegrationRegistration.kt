package io.github.amichne.kast.distribution.cli

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable private data class CodexServer(val name: String, val transport: CodexTransport)

@Serializable
private data class CodexTransport(val type: String, val command: String, val args: List<String> = emptyList())

@Serializable private data class BundledPayload(val path: String, val sha256: String, val mode: Int)

@Serializable
private data class BundledManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<BundledPayload>,
)

private val codexJson = Json { ignoreUnknownKeys = true }
private const val CODEX_DEADLINE_SECONDS = 8L
private const val CODEX_OUTPUT_LIMIT_BYTES = 262144

private sealed interface ProcessObservation {
    data class Exited(val code: Int, val output: String) : ProcessObservation

    data object Unavailable : ProcessObservation
}

private fun runBounded(arguments: List<String>): ProcessObservation {
    val process =
        try {
            ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
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
private fun codexRegistration(): CodexServer? {
    val response = runBounded(listOf("codex", "mcp", "list", "--json"))
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

private fun sourceFor(installation: Path, harness: Harness): Path =
    when (harness) {
        Harness.CODEX -> installation.resolve("bin/kast-mcp-complete")
        Harness.COPILOT -> installation.resolve("share/kast/adapters/copilot/extension.mjs")
        Harness.PI -> installation.resolve("share/kast/adapters/pi/extension.ts")
    }

private fun destinationFor(root: Path, home: Path, harness: Harness): Path =
    when (harness) {
        Harness.CODEX -> root.resolve("current/bin/kast-mcp-complete")
        Harness.COPILOT -> home.resolve(".copilot/extensions/kast/extension.mjs")
        Harness.PI -> home.resolve(".pi/agent/extensions/kast.ts")
    }

private fun selectedInstallation(root: Path): Path {
    val selected =
        try {
            root.resolve("current").toRealPath()
        } catch (_: Exception) {
            throw ManagementRejected("installation-admission", "selected installation is unavailable")
        }
    if (
        selected.parent != root.toRealPath().resolve("versions") ||
            !Files.isRegularFile(selected.resolve("installation.json"), LinkOption.NOFOLLOW_LINKS)
    )
        throw ManagementRejected("installation-admission", "selected installation is invalid")
    return selected
}

@Suppress("ThrowsCount", "ComplexCondition")
private fun verifiedBundledSource(installation: Path, harness: Harness): Path {
    val source = sourceFor(installation, harness)
    val raw =
        readBoundedFile(installation.resolve("installation.json"), 67_108_864)
            ?: throw ManagementRejected("connect", "release manifest is unavailable")
    val manifest =
        try {
            codexJson.decodeFromString<BundledManifest>(raw)
        } catch (_: SerializationException) {
            throw ManagementRejected("connect", "release manifest is invalid")
        }
    val relative = installation.relativize(source).toString().replace('\\', '/')
    val entries = manifest.payloadFiles.filter { it.path == relative }
    if (
        manifest.schemaVersion != 2 ||
            manifest.installationRoot != installation.toString() ||
            entries.size != 1 ||
            !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
            entries.single().sha256 != "sha256:${sha256(source)}"
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

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "ComplexCondition")
internal fun connectHarness(root: Path, home: Path, harness: Harness): Boolean =
    withRegistrationLock(root) {
        val receipt = admittedReceipt(root)
        val installation = selectedInstallation(root)
        val source = verifiedBundledSource(installation, harness)
        val digest = sha256(source)
        val destination = destinationFor(root, home, harness)
        val prior = receipt.registrations.singleOrNull { it.harness == harness }
        if (prior != null && prior.destination != destination.toString())
            throw ManagementRejected("connect", "recorded registration identity changed")
        var created = false
        var replacedBackup: Path? = null
        if (harness == Harness.CODEX) {
            val observed = codexRegistration()
            if (observed != null && !ownsCodex(observed, destination))
                throw ManagementRejected("connect", "Codex name belongs to another configuration")
            if (observed == null) {
                val add = runBounded(listOf("codex", "mcp", "add", "kast", "--", destination.toString()))
                if (add !is ProcessObservation.Exited || add.code != 0 || !ownsCodex(codexRegistration(), destination))
                    throw ManagementRejected("connect", "Codex registration was not verified")
                created = true
            }
        } else {
            val exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
            val current =
                if (exists && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) sha256(destination) else null
            if (exists && (prior == null || (current != digest && current != prior.payloadSha256)))
                throw ManagementRejected("connect", "integration file belongs to another owner")
            if (current != digest) {
                val parent = destination.parent
                Files.createDirectories(parent)
                val staged = Files.createTempFile(parent, ".kast-", ".new")
                try {
                    Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
                    if (exists) {
                        replacedBackup = Files.createTempFile(parent, ".kast-", ".prior")
                        Files.copy(destination, replacedBackup, StandardCopyOption.REPLACE_EXISTING)
                    }
                    if (exists)
                        Files.move(
                            staged,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    else Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE)
                    created = !exists
                } finally {
                    Files.deleteIfExists(staged)
                }
            }
            if (sha256(destination) != digest) throw ManagementRejected("connect", "registration verification failed")
        }
        val record = ManagedRegistration(harness, destination.toString(), digest)
        val next = receipt.copy(registrations = receipt.registrations.filterNot { it.harness == harness } + record)
        try {
            writeManagementReceipt(root, next)
        } catch (_: Exception) {
            if (harness == Harness.CODEX && created && ownsCodex(codexRegistration(), destination)) {
                val removal = runBounded(listOf("codex", "mcp", "remove", "kast"))
                if (removal !is ProcessObservation.Exited || removal.code != 0 || codexRegistration() != null)
                    throw ManagementRejected("connect", "receipt failed and Codex registration requires recovery")
            }
            if (
                harness != Harness.CODEX &&
                    Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) &&
                    sha256(destination) == digest
            ) {
                if (replacedBackup != null)
                    Files.move(
                        replacedBackup,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                else if (created) Files.delete(destination)
            }
            throw ManagementRejected("connect", "ownership receipt could not be committed")
        } finally {
            replacedBackup?.let { Files.deleteIfExists(it) }
        }
        prior == record
    }

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun disconnectHarness(root: Path, home: Path, harness: Harness): Boolean =
    withRegistrationLock(root) {
        val receipt = admittedReceipt(root)
        val prior = receipt.registrations.singleOrNull { it.harness == harness } ?: return@withRegistrationLock false
        val destination = destinationFor(root, home, harness)
        if (prior.destination != destination.toString())
            throw ManagementRejected("disconnect", "recorded registration identity changed")
        if (harness == Harness.CODEX) {
            val observed = codexRegistration()
            if (observed != null && !ownsCodex(observed, destination))
                throw ManagementRejected("disconnect", "Codex name belongs to another configuration")
            if (observed != null) {
                val remove = runBounded(listOf("codex", "mcp", "remove", "kast"))
                if (remove !is ProcessObservation.Exited || remove.code != 0 || codexRegistration() != null)
                    throw ManagementRejected("disconnect", "Codex removal was not verified")
            }
        } else if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (
                !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) ||
                    sha256(destination) != prior.payloadSha256
            )
                throw ManagementRejected("disconnect", "integration file is no longer owned")
            Files.delete(destination)
        }
        writeManagementReceipt(
            root,
            receipt.copy(registrations = receipt.registrations.filterNot { it.harness == harness }),
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
