package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.storage.PrivateRecordFailure
import io.github.amichne.kast.appserver.storage.PrivateRecordFiles
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/** Only a managed canonical service may publish desktop discovery into its GUI launchd domain. */
internal sealed interface DesktopDiscoveryTarget {
    data object NotRequired : DesktopDiscoveryTarget

    data class Managed(val directory: Path, val identity: BrokerServiceIdentity) : DesktopDiscoveryTarget

    /** Private managed services may withdraw existing ownership but cannot publish a flag. */
    data class CleanupOnly(val owner: Managed) : DesktopDiscoveryTarget

    companion object {
        fun from(options: InstalledCoordinatorOptions): DesktopDiscoveryTarget =
            when (val readiness = options.readiness) {
                BrokerServiceReadiness.Standalone -> NotRequired
                is BrokerServiceReadiness.Managed ->
                    from(options.publicEndpoint, Managed(options.serviceDirectory, readiness.identity))
            }

        fun from(command: BrokerServiceLaunchCommand): DesktopDiscoveryTarget =
            from(command.publicEndpoint, Managed(command.stateDirectory, command.identity))

        private fun from(endpoint: BrokerPublicEndpoint, owner: Managed): DesktopDiscoveryTarget =
            when (endpoint) {
                is BrokerPublicEndpoint.Private -> CleanupOnly(owner)
                is BrokerPublicEndpoint.CodexControl -> owner
            }
    }
}

internal enum class DesktopDiscoveryFailure {
    ENVIRONMENT_CONFLICT,
    COMMAND_REJECTED,
    COMMAND_TIMED_OUT,
    INTERRUPTED,
    READ_BACK_REJECTED,
    RECORD_REJECTED,
    RECORD_BUSY,
    RECORD_MALFORMED,
    OWNER_MISMATCH,
}

internal sealed interface DesktopDiscoveryOutcome {
    data object Ready : DesktopDiscoveryOutcome

    data class Rejected(val failure: DesktopDiscoveryFailure) : DesktopDiscoveryOutcome
}

internal enum class DesktopDaemonSetting {
    ABSENT,
    ENABLED,
    CONFLICTING,
}

internal sealed interface DesktopDaemonEnvironmentRead {
    data class Observed(val setting: DesktopDaemonSetting) : DesktopDaemonEnvironmentRead

    data class Rejected(val failure: DesktopDiscoveryFailure) : DesktopDaemonEnvironmentRead
}

/** The entire external capability concerns one fixed flag; arbitrary environment edits are excluded. */
internal interface DesktopDaemonEnvironment {
    fun read(): DesktopDaemonEnvironmentRead

    fun enable(): DesktopDiscoveryOutcome

    fun remove(): DesktopDiscoveryOutcome
}

@Serializable
internal enum class PriorDesktopDaemonSetting {
    ABSENT,
    ENABLED,
}

@Serializable
internal enum class DesktopDiscoveryRecordType {
    DESKTOP_DAEMON_DISCOVERY
}

/** Records the starting fact before the effect, never execution or desktop attachment success. */
@Serializable
internal data class DesktopDiscoveryRecord(
    val serviceIdentity: String,
    val previous: PriorDesktopDaemonSetting,
    val type: DesktopDiscoveryRecordType,
)

/** A decoded record is not ownership proof until its identity has been admitted and matched. */
internal class DesktopDiscoveryOwnership
private constructor(
    val identity: BrokerServiceIdentity,
    val previous: PriorDesktopDaemonSetting,
) {
    companion object {
        fun admit(
            record: DesktopDiscoveryRecord,
            expected: BrokerServiceIdentity,
        ): Refinement<DesktopDiscoveryOwnership, DesktopDiscoveryFailure> {
            val identity =
                BrokerServiceIdentity.admit(record.serviceIdentity)
                    ?: return Refinement.Rejected(DesktopDiscoveryFailure.RECORD_MALFORMED)
            if (identity != expected) return Refinement.Rejected(DesktopDiscoveryFailure.OWNER_MISMATCH)
            return Refinement.Refined(DesktopDiscoveryOwnership(identity, record.previous))
        }
    }
}

internal class DesktopDaemonDiscovery(
    private val environment: DesktopDaemonEnvironment = LaunchdDesktopDaemonEnvironment
) {
    fun enable(target: DesktopDiscoveryTarget): DesktopDiscoveryOutcome =
        when (target) {
            DesktopDiscoveryTarget.NotRequired -> DesktopDiscoveryOutcome.Ready
            is DesktopDiscoveryTarget.CleanupOnly -> release(target.owner)
            is DesktopDiscoveryTarget.Managed ->
                withRecord(target) { files, path -> enableManaged(files, path, target) }
        }

    private fun enableManaged(
        files: PrivateRecordFiles<DesktopDiscoveryFailure>,
        path: Path,
        target: DesktopDiscoveryTarget.Managed,
    ): Refinement<Unit, DesktopDiscoveryFailure> {
        val existing = Files.exists(path, NOFOLLOW_LINKS)
        if (existing) {
            when (val record = readOwnedRecord(files, path, target)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return record
            }
        }
        val current =
            when (val read = readStartingSetting()) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        if (!existing)
            files.write(
                path,
                DesktopDiscoveryRecord.serializer(),
                DesktopDiscoveryRecord(
                    target.identity.value,
                    current,
                    DesktopDiscoveryRecordType.DESKTOP_DAEMON_DISCOVERY,
                ),
                MAXIMUM_RECORD_BYTES,
            )
        return when (current) {
            PriorDesktopDaemonSetting.ABSENT -> mutateAndVerify(DesktopDaemonSetting.ENABLED, environment::enable)
            PriorDesktopDaemonSetting.ENABLED -> Refinement.Refined(Unit)
        }
    }

    private fun readStartingSetting(): Refinement<PriorDesktopDaemonSetting, DesktopDiscoveryFailure> =
        when (val read = environment.read()) {
            is DesktopDaemonEnvironmentRead.Rejected -> Refinement.Rejected(read.failure)
            is DesktopDaemonEnvironmentRead.Observed ->
                when (read.setting) {
                    DesktopDaemonSetting.ABSENT -> Refinement.Refined(PriorDesktopDaemonSetting.ABSENT)
                    DesktopDaemonSetting.ENABLED -> Refinement.Refined(PriorDesktopDaemonSetting.ENABLED)
                    DesktopDaemonSetting.CONFLICTING ->
                        Refinement.Rejected(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT)
                }
        }

    fun release(target: DesktopDiscoveryTarget): DesktopDiscoveryOutcome =
        when (target) {
            DesktopDiscoveryTarget.NotRequired -> DesktopDiscoveryOutcome.Ready
            is DesktopDiscoveryTarget.CleanupOnly -> release(target.owner)
            is DesktopDiscoveryTarget.Managed -> {
                val path = target.directory.resolve(RECORD_DIRECTORY).resolve(RECORD_NAME)
                // An older installation owns no flag. Do not create state or query launchd on its teardown.
                if (Files.notExists(path, NOFOLLOW_LINKS)) DesktopDiscoveryOutcome.Ready
                else withRecord(target) { files, recordPath -> releaseManaged(files, recordPath, target) }
            }
        }

    private fun releaseManaged(
        files: PrivateRecordFiles<DesktopDiscoveryFailure>,
        path: Path,
        target: DesktopDiscoveryTarget.Managed,
    ): Refinement<Unit, DesktopDiscoveryFailure> {
        val record =
            when (val read = readOwnedRecord(files, path, target)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        if (record.previous == PriorDesktopDaemonSetting.ABSENT) {
            when (val removed = releaseOwnedFlag()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return removed
            }
        }
        files.remove(path)
        return Refinement.Refined(Unit)
    }

    private fun readOwnedRecord(
        files: PrivateRecordFiles<DesktopDiscoveryFailure>,
        path: Path,
        target: DesktopDiscoveryTarget.Managed,
    ): Refinement<DesktopDiscoveryOwnership, DesktopDiscoveryFailure> {
        val record = files.read(path, DesktopDiscoveryRecord.serializer(), MAXIMUM_RECORD_BYTES)
        return DesktopDiscoveryOwnership.admit(record, target.identity)
    }

    private fun releaseOwnedFlag(): Refinement<Unit, DesktopDiscoveryFailure> =
        when (val read = environment.read()) {
            is DesktopDaemonEnvironmentRead.Rejected -> Refinement.Rejected(read.failure)
            is DesktopDaemonEnvironmentRead.Observed ->
                when (read.setting) {
                    DesktopDaemonSetting.ABSENT,
                    DesktopDaemonSetting.CONFLICTING -> Refinement.Refined(Unit)
                    DesktopDaemonSetting.ENABLED -> mutateAndVerify(DesktopDaemonSetting.ABSENT, environment::remove)
                }
        }

    private fun mutateAndVerify(
        expected: DesktopDaemonSetting,
        mutation: () -> DesktopDiscoveryOutcome,
    ): Refinement<Unit, DesktopDiscoveryFailure> {
        when (val outcome = mutation()) {
            DesktopDiscoveryOutcome.Ready -> Unit
            is DesktopDiscoveryOutcome.Rejected -> return Refinement.Rejected(outcome.failure)
        }
        return when (val read = environment.read()) {
            is DesktopDaemonEnvironmentRead.Rejected -> Refinement.Rejected(read.failure)
            is DesktopDaemonEnvironmentRead.Observed ->
                if (read.setting == expected) Refinement.Refined(Unit)
                else Refinement.Rejected(DesktopDiscoveryFailure.READ_BACK_REJECTED)
        }
    }

    private fun withRecord(
        target: DesktopDiscoveryTarget.Managed,
        action: (PrivateRecordFiles<DesktopDiscoveryFailure>, Path) -> Refinement<Unit, DesktopDiscoveryFailure>,
    ): DesktopDiscoveryOutcome {
        val files =
            when (val opened = PrivateRecordFiles.open(target.directory.resolve(RECORD_DIRECTORY), ::recordFailure)) {
                is Refinement.Refined -> opened.value
                is Refinement.Rejected -> return DesktopDiscoveryOutcome.Rejected(opened.failure)
            }
        return when (val result = files.locked { action(files, files.root.resolve(RECORD_NAME)) }) {
            is Refinement.Refined -> DesktopDiscoveryOutcome.Ready
            is Refinement.Rejected -> DesktopDiscoveryOutcome.Rejected(result.failure)
        }
    }

    private fun recordFailure(failure: PrivateRecordFailure): DesktopDiscoveryFailure =
        when (failure) {
            PrivateRecordFailure.STORE_REJECTED -> DesktopDiscoveryFailure.RECORD_REJECTED
            PrivateRecordFailure.STORE_BUSY -> DesktopDiscoveryFailure.RECORD_BUSY
            PrivateRecordFailure.DOCUMENT_MALFORMED -> DesktopDiscoveryFailure.RECORD_MALFORMED
        }

    companion object {
        private const val RECORD_DIRECTORY = "desktop-discovery"
        private const val RECORD_NAME = "ownership.json"
        private const val MAXIMUM_RECORD_BYTES = 512
    }
}

/** Called by the existing GUI-domain service; this does not launch or modify ChatGPT. */
internal object LaunchdDesktopDaemonEnvironment : DesktopDaemonEnvironment {
    override fun read(): DesktopDaemonEnvironmentRead =
        when (val result = run("getenv")) {
            is CommandResult.Rejected -> DesktopDaemonEnvironmentRead.Rejected(result.failure)
            is CommandResult.Completed -> interpretRead(result.exitCode, result.output)
        }

    internal fun interpretRead(exitCode: Int, output: String): DesktopDaemonEnvironmentRead =
        when {
            exitCode != 0 -> DesktopDaemonEnvironmentRead.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
            output.isEmpty() -> DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ABSENT)
            output == "1\n" -> DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ENABLED)
            else -> DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.CONFLICTING)
        }

    override fun enable(): DesktopDiscoveryOutcome = mutation("setenv", "1")

    override fun remove(): DesktopDiscoveryOutcome = mutation("unsetenv")

    private fun mutation(operation: String, vararg values: String): DesktopDiscoveryOutcome =
        when (val result = run(operation, *values)) {
            is CommandResult.Rejected -> DesktopDiscoveryOutcome.Rejected(result.failure)
            is CommandResult.Completed ->
                if (result.exitCode == 0 && result.output.isEmpty()) DesktopDiscoveryOutcome.Ready
                else DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
        }

    private fun run(operation: String, vararg values: String): CommandResult {
        val process =
            try {
                ProcessBuilder(listOf("/bin/launchctl", operation, "CODEX_APP_SERVER_USE_LOCAL_DAEMON") + values)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: IOException) {
                return CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
            } catch (_: SecurityException) {
                return CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
            }
        return try {
            if (!process.waitFor(BrokerOperationalLimits.desktopInspection.value, TimeUnit.MILLISECONDS))
                CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_TIMED_OUT)
            else {
                val bytes =
                    process.inputStream.use { it.readNBytes(BrokerOperationalLimits.maximumDesktopInspectionBytes + 1) }
                if (bytes.size > BrokerOperationalLimits.maximumDesktopInspectionBytes)
                    CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
                else CommandResult.Completed(process.exitValue(), bytes.toString(Charsets.UTF_8))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CommandResult.Rejected(DesktopDiscoveryFailure.INTERRUPTED)
        } catch (_: IOException) {
            CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private sealed interface CommandResult {
        data class Completed(val exitCode: Int, val output: String) : CommandResult

        data class Rejected(val failure: DesktopDiscoveryFailure) : CommandResult
    }
}
