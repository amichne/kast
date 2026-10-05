package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.storage.PrivateRecordFailure
import io.github.amichne.kast.appserver.storage.PrivateRecordFiles
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Only a managed canonical service with matching desktop routing may publish discovery. */
internal data class DesktopDiscoveryOwner(val directory: Path, val identity: BrokerServiceIdentity)

internal sealed interface DesktopDiscoveryTarget {
    data object NotRequired : DesktopDiscoveryTarget

    data class Publish(
        val owner: DesktopDiscoveryOwner,
        val socket: CodexControlSocketPath,
        val defaultSocket: CodexControlSocketPath,
    ) : DesktopDiscoveryTarget

    data class CleanupOnly(val owner: DesktopDiscoveryOwner) : DesktopDiscoveryTarget

    companion object {
        fun from(options: InstalledCoordinatorOptions): DesktopDiscoveryTarget =
            when (val readiness = options.readiness) {
                BrokerServiceReadiness.Standalone -> NotRequired
                is BrokerServiceReadiness.Managed ->
                    from(
                        options.publicEndpoint,
                        options.userHome,
                        DesktopDiscoveryOwner(options.serviceDirectory, readiness.identity),
                    )
            }

        fun from(command: BrokerServiceLaunchCommand): DesktopDiscoveryTarget =
            from(
                command.publicEndpoint,
                command.userHome,
                DesktopDiscoveryOwner(command.stateDirectory, command.identity),
            )

        private fun from(
            endpoint: BrokerPublicEndpoint,
            userHome: Path,
            owner: DesktopDiscoveryOwner,
        ): DesktopDiscoveryTarget =
            when (endpoint) {
                is BrokerPublicEndpoint.Private -> CleanupOnly(owner)
                is BrokerPublicEndpoint.CodexControl ->
                    Publish(owner, endpoint.socket, CodexControlSocketPath.from(userHome.resolve(".codex")))
            }
    }
}

internal sealed interface DesktopDaemonHomeRead {
    data object Default : DesktopDaemonHomeRead

    data class Configured(val socket: CodexControlSocketPath) : DesktopDaemonHomeRead

    data class Rejected(val failure: DesktopDiscoveryFailure) : DesktopDaemonHomeRead
}

internal enum class DesktopDiscoveryFailure {
    ENVIRONMENT_CONFLICT,
    HOME_MISMATCH,
    HOME_REJECTED,
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
    fun readHome(): DesktopDaemonHomeRead

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
            is DesktopDiscoveryTarget.Publish ->
                withRecord(target.owner) { files, path -> enableManaged(files, path, target) }
        }

    private fun enableManaged(
        files: PrivateRecordFiles<DesktopDiscoveryFailure>,
        path: Path,
        target: DesktopDiscoveryTarget.Publish,
    ): Refinement<Unit, DesktopDiscoveryFailure> {
        val existing = Files.exists(path, NOFOLLOW_LINKS)
        val retained =
            if (existing) {
                when (val record = readOwnedRecord(files, path, target.owner)) {
                    is Refinement.Refined -> record.value
                    is Refinement.Rejected -> return record
                }
            } else null
        when (val route = qualifyHome(target)) {
            DesktopDiscoveryOutcome.Ready -> Unit
            is DesktopDiscoveryOutcome.Rejected -> return Refinement.Rejected(route.failure)
        }
        val current =
            when (val read = readStartingSetting()) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        if (retained?.previous == PriorDesktopDaemonSetting.ENABLED && current == PriorDesktopDaemonSetting.ABSENT)
            return Refinement.Rejected(DesktopDiscoveryFailure.ENVIRONMENT_CONFLICT)
        if (!existing)
            files.write(
                path,
                DesktopDiscoveryRecord.serializer(),
                DesktopDiscoveryRecord(
                    target.owner.identity.value,
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

    private fun qualifyHome(target: DesktopDiscoveryTarget.Publish): DesktopDiscoveryOutcome {
        val observed =
            when (val read = environment.readHome()) {
                DesktopDaemonHomeRead.Default -> target.defaultSocket
                is DesktopDaemonHomeRead.Configured -> read.socket
                is DesktopDaemonHomeRead.Rejected -> return DesktopDiscoveryOutcome.Rejected(read.failure)
            }
        return if (observed.path == target.socket.path) DesktopDiscoveryOutcome.Ready
        else DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.HOME_MISMATCH)
    }

    fun release(target: DesktopDiscoveryTarget): DesktopDiscoveryOutcome =
        when (target) {
            DesktopDiscoveryTarget.NotRequired -> DesktopDiscoveryOutcome.Ready
            is DesktopDiscoveryTarget.CleanupOnly -> release(target.owner)
            is DesktopDiscoveryTarget.Publish -> release(target.owner)
        }

    fun release(owner: DesktopDiscoveryOwner): DesktopDiscoveryOutcome {
        val path = owner.directory.resolve(RECORD_DIRECTORY).resolve(RECORD_NAME)
        if (Files.notExists(path, NOFOLLOW_LINKS)) return DesktopDiscoveryOutcome.Ready
        return withRecord(owner) { files, recordPath -> releaseManaged(files, recordPath, owner) }
    }

    private fun releaseManaged(
        files: PrivateRecordFiles<DesktopDiscoveryFailure>,
        path: Path,
        target: DesktopDiscoveryOwner,
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
        target: DesktopDiscoveryOwner,
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
        target: DesktopDiscoveryOwner,
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
