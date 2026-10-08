package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

private const val EXECUTABLE_PERMISSION_MASK = 73

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

internal fun admittedReceipt(root: Path): ManagementReceipt =
    when (val read = readReceipt(root)) {
        is ReceiptRead.Read -> read.receipt
        is ReceiptRead.Unavailable -> throw ManagementRejected("installation-admission", read.reason)
    }

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
    ownership: RegistrationOwnership = RegistrationOwnership.RECOVER_SELECTED_SLOT,
    codexHome: Path = home.resolve(".codex"),
    executeCodex: ((List<String>) -> ProcessObservation)? = null,
    directory: ConnectionDirectory? = null,
    environment: Map<String, String> = emptyMap(),
    executeCodexAt: (Path, List<String>) -> ProcessObservation = { selected, arguments ->
        runBounded(arguments, selected)
    },
    recoveryEvidence: (ConnectionRecoveryEvidence) -> Unit = ::printConnectionRecovery,
    commitConfiguration: (Path, ConnectionConfiguration) -> Unit = ::writeConnectionConfiguration,
    commitReceipt: (Path, ManagementReceipt) -> Unit = ::writeManagementReceipt,
): Boolean =
    withRegistrationLock(root) {
        if (Files.exists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS))
            throw ManagementRejected(
                "connection-registration",
                "installation removal is unfinished; retry uninstall first",
            )
        val receipt = admittedReceipt(root)
        val source = verifiedBundledSource(selectedInstallation(root), connection)
        val payload = registrationPayload(root, source, connection)
        val digest = payload.digest
        val prior = receipt.registrations.singleOrNull { it.connection == connection }
        val configurationRead = readConnectionConfiguration(home)
        val configuration =
            when (configurationRead) {
                is ConnectionConfigurationRead.Read -> configurationRead.configuration
                is ConnectionConfigurationRead.Recovered -> configurationRead.configuration
                is ConnectionConfigurationRead.Rejected -> {
                    when (val recovered = recoverConnectionConfiguration(root, receipt)) {
                        is ConnectionConfigurationAdmission.Admitted -> recovered.configuration
                        is ConnectionConfigurationAdmission.Rejected -> {
                            if (directory == null)
                                throw ManagementRejected(
                                    "connection-configuration",
                                    configurationRead.failure.explanation,
                                )
                            ConnectionConfiguration.empty()
                        }
                    }
                }
            }
        val selectedDirectory =
            connectionDirectoryFor(
                root,
                home,
                connection,
                configuration,
                environment,
                directory,
                codexHome,
                prior,
            )
        val destination = registrationDestinationFor(root, connection, selectedDirectory)
        val executeSelected: (List<String>) -> ProcessObservation =
            executeCodex ?: { arguments -> executeCodexAt(selectedDirectory.path, arguments) }
        val force = ownership != RegistrationOwnership.REQUIRE_OWNED
        val migration =
            if (
                connection == HarnessConnection.CODEX_MCP &&
                    ownership != RegistrationOwnership.REPLACE_SELECTED_SLOT &&
                    prior != null &&
                    prior.destination != destination.toString()
            ) {
                when (val admitted = LegacyMcpMigration.admit(root, prior)) {
                    is LegacyConnectionAdmission.Admitted -> admitted.migration
                    is LegacyConnectionAdmission.Rejected ->
                        throw ManagementRejected("connect-migration", admitted.failure.name.lowercase())
                }
            } else null
        val selectedFile =
            if (connection == HarnessConnection.CODEX_MCP) selectedDirectory.path.resolve("config.toml")
            else destination
        val registrationAnchor = if (selectedDirectory.path.startsWith(home)) home else selectedDirectory.path
        requireRegistrationPath(selectedFile, registrationAnchor)
        val existing = Files.exists(selectedFile, LinkOption.NOFOLLOW_LINKS)
        val current = if (existing) sha256(selectedFile) else null
        val replace =
            if (connection == HarnessConnection.CODEX_MCP) {
                val observed = codexRegistration(executeSelected)
                if (migration != null && !ownsCodex(observed, migration.command))
                    throw ManagementRejected(
                        "connect-migration",
                        LegacyConnectionRejection.LEGACY_REGISTRATION_UNVERIFIED.name.lowercase(),
                    )
                if (!force && migration == null && observed != null && !ownsCodex(observed, destination))
                    throw ManagementRejected("connect", "Codex name belongs to another configuration")
                !ownsCodex(observed, destination)
            } else {
                if (
                    !force &&
                        existing &&
                        (prior == null ||
                            prior.destination != destination.toString() ||
                            (current != digest && current != prior.payloadSha256))
                )
                    throw ManagementRejected("connect", "integration file belongs to another owner")
                current != digest || (payload is RegistrationPayload.Launcher && !Files.isExecutable(destination))
            }
        val record = ManagedRegistration(connection, destination.toString(), digest, selectedDirectory.path.toString())
        val next =
            receipt.copy(registrations = receipt.registrations.filterNot { it.connection == connection } + record)
        val oldDirectory = prior?.directory?.let(::admittedConnectionDirectory) ?: configuration.directory(connection)
        val oldFile =
            when {
                connection == HarnessConnection.CODEX_MCP &&
                    prior != null &&
                    oldDirectory != null &&
                    oldDirectory != selectedDirectory -> oldDirectory.path.resolve("config.toml")
                connection != HarnessConnection.CODEX_MCP &&
                    prior != null &&
                    prior.destination != destination.toString() -> admittedConnectionDirectory(prior.destination).path
                else -> null
            }
        val oldAnchor =
            if (prior != null && connection != HarnessConnection.CODEX_MCP)
                recordedFileDirectory(root, connection, prior).path
            else oldDirectory?.path ?: home
        if (oldFile != null) {
            requireRegistrationPath(oldFile, if (oldFile.startsWith(home)) home else oldAnchor)
            if (connection == HarnessConnection.CODEX_MCP) {
                val executeOld = executeCodex ?: { arguments: List<String> -> executeCodexAt(oldAnchor, arguments) }
                val observed = codexRegistration(executeOld)
                if (observed != null && !ownsCodex(observed, destination))
                    throw ManagementRejected(
                        "connect",
                        "previous Codex slot changed; preserve it and choose another destination",
                    )
            } else if (Files.exists(oldFile, LinkOption.NOFOLLOW_LINKS) && sha256(oldFile) != prior?.payloadSha256) {
                throw ManagementRejected(
                    "connect",
                    "previous connection changed; preserve it before moving the connection",
                )
            }
        }
        // Capture both preimages before any replacement. Codex's full configuration is opaque:
        // restoring these bytes preserves fields outside the inspection DTO, including credentials.
        val registration = captureRegistrationPreimage(selectedFile, registrationAnchor)
        val additionalPreimages = mutableListOf<RegistrationPreimage>()
        val configPath = connectionConfigurationPath(home)
        val checkpointPath = connectionCheckpointPath(home)
        val receiptPreimage =
            try {
                additionalPreimages += captureRegistrationPreimage(configPath, home)
                additionalPreimages += captureRegistrationPreimage(checkpointPath, home)
                if (oldFile != null)
                    additionalPreimages +=
                        captureRegistrationPreimage(
                            oldFile,
                            if (oldFile.startsWith(home)) home else oldAnchor,
                        )
                captureRegistrationPreimage(receiptPath(root), root)
            } catch (failure: Exception) {
                registration.discard()
                additionalPreimages.forEach { it.discard() }
                throw failure
            }
        var registrationAfter = registrationPostimage(selectedFile)
        val additionalPostimages =
            additionalPreimages.associateWith { registrationPostimage(it.destination) }.toMutableMap()
        try {
            if (replace) {
                if (connection == HarnessConnection.CODEX_MCP) {
                    val add =
                        try {
                            executeSelected(listOf("codex", "mcp", "add", "kast", "--", destination.toString()))
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
                if (!ownsCodex(codexRegistration(executeSelected), destination))
                    throw ManagementRejected("connect", "Codex registration was not verified")
            } else if (sha256(destination) != digest) {
                throw ManagementRejected("connect", "registration verification failed")
            }
            if (oldFile != null) {
                val oldPreimage = additionalPreimages.single { it.destination == oldFile }
                if (connection == HarnessConnection.CODEX_MCP) {
                    val executeOld = executeCodex ?: { arguments: List<String> -> executeCodexAt(oldAnchor, arguments) }
                    val observed = codexRegistration(executeOld)
                    if (observed != null) {
                        if (!ownsCodex(observed, destination))
                            throw ManagementRejected("connect", "previous Codex slot changed during relocation")
                        val remove =
                            try {
                                executeOld(listOf("codex", "mcp", "remove", "kast"))
                            } finally {
                                additionalPostimages[oldPreimage] = registrationPostimage(oldFile)
                            }
                        if (
                            remove !is ProcessObservation.Exited ||
                                remove.code != 0 ||
                                codexRegistration(executeOld) != null
                        )
                            throw ManagementRejected("connect", "previous Codex slot removal was not verified")
                    }
                } else {
                    requireRegistrationPath(oldFile, oldPreimage.anchor)
                    if (registrationPostimage(oldFile) != additionalPostimages.getValue(oldPreimage))
                        throw ManagementRejected("connect", "previous connection changed during relocation")
                    Files.deleteIfExists(oldFile)
                    additionalPostimages[oldPreimage] = RegistrationPostimage.Absent
                }
            }
            val nextConfiguration = configuration.selecting(connection, selectedDirectory)
            for (path in listOf(configPath, checkpointPath)) {
                val preimage = additionalPreimages.single { it.destination == path }
                requireRegistrationPath(path, home)
                if (registrationPostimage(path) != additionalPostimages.getValue(preimage))
                    throw ManagementRejected("connect", "saved configuration changed during connection")
                additionalPostimages[preimage] =
                    RegistrationPostimage.Present(sha256Text(encodeConnectionConfiguration(nextConfiguration)))
                commitConfiguration(path, nextConfiguration)
                val encoded = readBoundedFile(path, 65536)
                if (
                    encoded == null ||
                        managementJson.decodeFromString<ConnectionConfigurationDocument>(encoded) !=
                            nextConfiguration.document()
                )
                    throw ManagementRejected("connect", "saved connection configuration was not verified")
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
                    .plus(additionalPreimages.asReversed().map { it.restore(additionalPostimages.getValue(it)) })
                    .filterIsInstance<RegistrationRecovery.Required>()
            if (recovery.isNotEmpty())
                throw ManagementRejected("connect-recovery", recovery.joinToString("; ") { it.description })
            recoveryEvidence(ConnectionRecoveryEvidence.TransactionRestored(connection))
            if (failure is ManagementRejected) throw failure
            throw ManagementRejected("connect", "ownership transaction failed; exact preimages restored")
        }
        // Recovery retains displaced bytes even after successful activation; the backup is private.
        val displaced =
            existing &&
                (prior == null ||
                    prior.destination != destination.toString() ||
                    current != prior.payloadSha256 ||
                    connection == HarnessConnection.CODEX_MCP && replace)
        if (displaced && registration is RegistrationPreimage.Present)
            recoveryEvidence(ConnectionRecoveryEvidence.BackupRetained(selectedFile, registration.backup))
        else registration.discard()
        for (preimage in additionalPreimages) {
            val rejectedConfiguration =
                configurationRead is ConnectionConfigurationRead.Recovered && preimage.destination == configPath
            val rebuiltConfiguration =
                configurationRead is ConnectionConfigurationRead.Rejected &&
                    preimage.destination in listOf(configPath, checkpointPath)
            if ((rejectedConfiguration || rebuiltConfiguration) && preimage is RegistrationPreimage.Present)
                recoveryEvidence(ConnectionRecoveryEvidence.BackupRetained(preimage.destination, preimage.backup))
            else preimage.discard()
        }
        if (configurationRead is ConnectionConfigurationRead.Recovered)
            recoveryEvidence(ConnectionRecoveryEvidence.ConfigurationRecovered(checkpointPath))
        if (configurationRead is ConnectionConfigurationRead.Rejected)
            recoveryEvidence(ConnectionRecoveryEvidence.ConfigurationRebuilt(configPath))
        receiptPreimage.discard()
        prior == record && !replace
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
