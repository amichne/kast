package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.storage.PrivateRecordFailure
import io.github.amichne.kast.appserver.storage.PrivateRecordFiles
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Reads only the addressed hash shard; historical records never populate the daemon's active cache. */
internal class FileInvocationRecords
private constructor(private val files: PrivateRecordFiles<InvocationFenceFailure>) : InvocationRecords {
    private val records = files.root.resolve(RECORDS)

    override fun apply(change: InvocationRecordChange): InvocationAdmission =
        when (
            val result = files.locked {
                files.requireDirectory(records)
                val shard = records.resolve(change.key.value.take(SHARD_CHARACTERS))
                val path = shard.resolve("${change.key.value}.json")
                val existing =
                    when (val read = lookup(shard, path, change.key)) {
                        is Refinement.Rejected -> return@locked read
                        is Refinement.Refined -> read.value
                    }
                when (val transition = invocationTransition(existing, change)) {
                    is Refinement.Rejected -> transition
                    is Refinement.Refined -> {
                        files.directory(shard)
                        files.write(
                            path,
                            InvocationRecordDocument.serializer(),
                            InvocationRecordDocument.from(transition.value),
                            maximumBytes = MAXIMUM_RECORD_BYTES,
                        )
                        Refinement.Refined(Unit)
                    }
                }
            }
        ) {
            is Refinement.Refined -> InvocationAdmission.Admitted
            is Refinement.Rejected -> InvocationAdmission.Rejected(result.failure)
        }

    private fun lookup(
        shard: Path,
        path: Path,
        key: InvocationKey,
    ): Refinement<InvocationRecordLookup, InvocationFenceFailure> {
        if (!Files.exists(shard, NOFOLLOW_LINKS)) return Refinement.Refined(InvocationRecordLookup.Absent)
        files.requireDirectory(shard)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Refinement.Refined(InvocationRecordLookup.Absent)
        return when (
            val admitted = files.read(path, InvocationRecordDocument.serializer(), MAXIMUM_RECORD_BYTES).admit(key)
        ) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> Refinement.Refined(InvocationRecordLookup.Present(admitted.value))
        }
    }

    companion object {
        private const val RECORDS = "records-v2"
        private const val LAYOUT = "layout.json"
        private const val MAXIMUM_RECORD_BYTES = 1024
        private const val SHARD_CHARACTERS = 2

        fun open(
            legacy: Path,
            observe: (InvocationMigrationObservation) -> Unit,
        ): Refinement<InvocationRecords, InvocationFenceFailure> {
            val files =
                when (
                    val admitted =
                        PrivateRecordFiles.open(legacy.resolveSibling("${legacy.fileName}.d"), ::storageFailure)
                ) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> admitted.value
                }
            return files.locked {
                val marker = files.root.resolve(LAYOUT)
                if (Files.exists(marker, NOFOLLOW_LINKS)) {
                    val layout = files.read(marker, InvocationStoreLayout.serializer(), MAXIMUM_RECORD_BYTES)
                    if (layout.schemaVersion != InvocationRecordDocument.VERSION)
                        return@locked Refinement.Rejected(InvocationFenceFailure.VERSION_UNSUPPORTED)
                    files.requireDirectory(files.root.resolve(RECORDS))
                } else {
                    val migrated = observedMigration(files, legacy, observe)
                    when (migrated) {
                        is Refinement.Rejected -> return@locked migrated
                        is Refinement.Refined -> Unit
                    }
                }
                Refinement.Refined(FileInvocationRecords(files))
            }
        }

        private fun storageFailure(failure: PrivateRecordFailure): InvocationFenceFailure =
            when (failure) {
                PrivateRecordFailure.STORE_BUSY -> InvocationFenceFailure.STORE_BUSY
                PrivateRecordFailure.STORE_REJECTED -> InvocationFenceFailure.STORE_REJECTED
                PrivateRecordFailure.DOCUMENT_MALFORMED -> InvocationFenceFailure.DOCUMENT_MALFORMED
            }

        private fun observedMigration(
            files: PrivateRecordFiles<InvocationFenceFailure>,
            legacy: Path,
            observe: (InvocationMigrationObservation) -> Unit,
        ): Refinement<Unit, InvocationFenceFailure> {
            observe(InvocationMigrationObservation(InvocationMigrationOutcome.Started))
            val result = files.guarded { migrate(files, legacy) }
            val outcome =
                when (result) {
                    is Refinement.Refined -> InvocationMigrationOutcome.Committed
                    is Refinement.Rejected -> InvocationMigrationOutcome.Rejected(result.failure)
                }
            observe(InvocationMigrationObservation(outcome))
            return result
        }

        private fun admitLegacy(
            source: LegacyInvocationDocument
        ): Refinement<List<InvocationRecord>, InvocationFenceFailure> {
            if (source.schemaVersion != 1) return Refinement.Rejected(InvocationFenceFailure.VERSION_UNSUPPORTED)
            if (source.records.size > BrokerOperationalLimits.maximumInvocations)
                return Refinement.Rejected(InvocationFenceFailure.MIGRATION_REJECTED)
            val admitted = mutableListOf<InvocationRecord>()
            for ((key, record) in source.records) {
                val identity =
                    when (val value = InvocationKey.admit(key)) {
                        is Refinement.Rejected -> return Refinement.Rejected(InvocationFenceFailure.MIGRATION_REJECTED)
                        is Refinement.Refined -> value.value
                    }
                when (
                    val value =
                        InvocationRecordDocument(
                                InvocationRecordDocument.VERSION,
                                key,
                                record.fingerprint,
                                record.phase,
                            )
                            .admit(identity)
                ) {
                    is Refinement.Rejected -> return Refinement.Rejected(InvocationFenceFailure.MIGRATION_REJECTED)
                    is Refinement.Refined -> admitted += value.value
                }
            }
            return Refinement.Refined(admitted)
        }

        private fun stage(files: PrivateRecordFiles<InvocationFenceFailure>, admitted: List<InvocationRecord>): Path {
            val stage = files.createStage()
            for (record in admitted) {
                val shard = stage.resolve(record.key.value.take(SHARD_CHARACTERS))
                files.directory(shard)
                val path = shard.resolve("${record.key.value}.json")
                val document = InvocationRecordDocument.from(record)
                files.write(path, InvocationRecordDocument.serializer(), document, maximumBytes = MAXIMUM_RECORD_BYTES)
                if (files.read(path, InvocationRecordDocument.serializer(), MAXIMUM_RECORD_BYTES) != document)
                    throw java.io.IOException("Staged record verification failed")
            }
            return stage
        }

        private fun migrate(
            files: PrivateRecordFiles<InvocationFenceFailure>,
            legacy: Path,
        ): Refinement<Unit, InvocationFenceFailure> {
            // A previous interrupted migration retains its stage and source for explicit recovery.
            Files.newDirectoryStream(files.root).use { entries ->
                if (entries.any { it.fileName.toString() != ".lock" })
                    return Refinement.Rejected(InvocationFenceFailure.MIGRATION_REJECTED)
            }
            val source =
                if (Files.exists(legacy, NOFOLLOW_LINKS))
                    files.read(
                        legacy,
                        LegacyInvocationDocument.serializer(),
                        BrokerOperationalLimits.maximumInvocationJournalBytes,
                    )
                else LegacyInvocationDocument(1, emptyMap())
            val admitted =
                when (val validated = admitLegacy(source)) {
                    is Refinement.Rejected -> return validated
                    is Refinement.Refined -> validated.value
                }
            val stage = stage(files, admitted)
            if (
                Files.exists(legacy, NOFOLLOW_LINKS) &&
                    files.read(
                        legacy,
                        LegacyInvocationDocument.serializer(),
                        BrokerOperationalLimits.maximumInvocationJournalBytes,
                    ) != source
            )
                return Refinement.Rejected(InvocationFenceFailure.MIGRATION_REJECTED)
            files.publishStage(stage, files.root.resolve(RECORDS))
            files.write(
                files.root.resolve(LAYOUT),
                InvocationStoreLayout.serializer(),
                InvocationStoreLayout(InvocationRecordDocument.VERSION),
                maximumBytes = MAXIMUM_RECORD_BYTES,
            )
            return Refinement.Refined(Unit)
        }
    }
}
