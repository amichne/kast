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
private data class CodexTransport(val type: String, val command: String = "", val args: List<String> = emptyList())

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

internal sealed interface ProcessObservation {
    data class Exited(val code: Int, val output: String) : ProcessObservation

    data object Unavailable : ProcessObservation
}

internal fun runBounded(arguments: List<String>, codexHome: Path? = null): ProcessObservation {
    val process =
        try {
            ProcessBuilder(arguments).apply {
                if (codexHome != null) environment()["CODEX_HOME"] = codexHome.toString()
            }.redirectError(ProcessBuilder.Redirect.DISCARD).start()
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
internal fun connectHarness(
    root: Path,
    home: Path,
    harness: Harness,
    ownership: RegistrationOwnership = RegistrationOwnership.REQUIRE_OWNED,
    codexHome: Path = System.getenv("CODEX_HOME")?.let(Path::of) ?: home.resolve(".codex"),
    executeCodex: (List<String>) -> ProcessObservation = { runBounded(it, codexHome) },
    commitReceipt: (Path, ManagementReceipt) -> Unit = ::writeManagementReceipt,
): Boolean = withRegistrationLock(root) {
    val receipt = admittedReceipt(root)
    val source = verifiedBundledSource(selectedInstallation(root), harness)
    val digest = sha256(source)
    val destination = destinationFor(root, home, harness)
    val prior = receipt.registrations.singleOrNull { it.harness == harness }
    val force = ownership == RegistrationOwnership.REPLACE_SELECTED_SLOT
    if (!force && prior != null && prior.destination != destination.toString())
        throw ManagementRejected("connect", "recorded registration identity changed")
    val selectedFile = if (harness == Harness.CODEX) codexHome.resolve("config.toml") else destination
    val registrationAnchor = if (harness == Harness.CODEX) codexHome else home
    requireRegistrationPath(selectedFile, registrationAnchor)
    val existing = Files.exists(selectedFile, LinkOption.NOFOLLOW_LINKS)
    val current = if (existing) sha256(selectedFile) else null
    val replace = if (harness == Harness.CODEX) {
        val observed = codexRegistration(executeCodex)
        if (!force && observed != null && !ownsCodex(observed, destination))
            throw ManagementRejected("connect", "Codex name belongs to another configuration")
        !ownsCodex(observed, destination)
    } else {
        if (!force && existing && (prior == null || (current != digest && current != prior.payloadSha256)))
            throw ManagementRejected("connect", "integration file belongs to another owner")
        current != digest
    }
    val record = ManagedRegistration(harness, destination.toString(), digest)
    val next = receipt.copy(registrations = receipt.registrations.filterNot { it.harness == harness } + record)
    // Capture both preimages before any replacement. Codex's full configuration is opaque:
    // restoring these bytes preserves fields outside the inspection DTO, including credentials.
    val registration = RegistrationPreimage.capture(selectedFile, registrationAnchor)
    val receiptPreimage = try { RegistrationPreimage.capture(receiptPath(root), root) } catch (failure: Exception) {
        registration.discard()
        throw failure
    }
    try {
        if (replace) {
            if (harness == Harness.CODEX) {
                val add = executeCodex(listOf("codex", "mcp", "add", "kast", "--", destination.toString()))
                if (add !is ProcessObservation.Exited || add.code != 0)
                    throw ManagementRejected("connect", "Codex registration replacement failed")
            } else {
                val staged = Files.createTempFile(destination.parent, ".kast-", ".new")
                try {
                    Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
                    if (sha256(staged) != digest) throw ManagementRejected("connect", "staged payload is unverified")
                    Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(staged) }
            }
        }
        if (harness == Harness.CODEX) {
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
        val recovery = listOf(registration.restore(), receiptPreimage.restore()).filterIsInstance<RegistrationRecovery.Required>()
        if (recovery.isNotEmpty()) throw ManagementRejected("connect-recovery", recovery.joinToString("; ") { it.description })
        if (failure is ManagementRejected) throw failure
        throw ManagementRejected("connect", "ownership transaction failed; exact preimages restored")
    }
    registration.discard()
    receiptPreimage.discard()
    prior == record && !replace
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
