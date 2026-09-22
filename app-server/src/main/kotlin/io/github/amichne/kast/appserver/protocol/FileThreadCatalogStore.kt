package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.storage.PrivateRecordFailure
import io.github.amichne.kast.appserver.storage.PrivateRecordFiles
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Persistent history is read by thread digest; only the requested current binding acquires filesystem proof. */
internal class FileThreadCatalogStore
private constructor(private val files: PrivateRecordFiles<ThreadCatalogStoreFailure>) : ThreadCatalogStore {
    private val records = files.root.resolve(RECORDS)

    override suspend fun read(threadId: String): ThreadStoreRead =
        withContext(Dispatchers.IO) {
            val thread =
                BrokerThreadId.admit(threadId)
                    ?: return@withContext ThreadStoreRead.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
            when (val result = files.locked { lookup(ThreadRecordKey.of(thread)) }) {
                is Refinement.Rejected -> ThreadStoreRead.Rejected(result.failure)
                is Refinement.Refined ->
                    when (val record = result.value) {
                        ThreadRecordLookup.Absent -> ThreadStoreRead.Missing
                        is ThreadRecordLookup.Present -> readBinding(record.binding)
                    }
            }
        }

    private fun readBinding(binding: StoredThreadBinding): ThreadStoreRead =
        when (binding) {
            is StoredThreadBinding.Legacy ->
                ThreadStoreRead.Rejected(ThreadCatalogStoreFailure.NEW_CONVERSATION_REQUIRED)
            is StoredThreadBinding.Current ->
                when (val admitted = binding.document.admit()) {
                    is Refinement.Rejected -> ThreadStoreRead.Rejected(admitted.failure)
                    is Refinement.Refined -> ThreadStoreRead.Found(admitted.value)
                }
        }

    override suspend fun write(binding: ThreadCatalogBinding): ThreadStoreWrite =
        withContext(Dispatchers.IO) {
            val key = ThreadRecordKey.of(binding.threadId)
            val document = ThreadBindingDocument.from(binding)
            val result = files.locked {
                when (val previous = lookup(key)) {
                    is Refinement.Rejected -> previous
                    is Refinement.Refined -> persist(key, document, previous.value)
                }
            }
            when (result) {
                is Refinement.Refined -> ThreadStoreWrite.Written
                is Refinement.Rejected -> ThreadStoreWrite.Rejected(result.failure)
            }
        }

    private fun persist(
        key: ThreadRecordKey,
        document: ThreadBindingDocument,
        previous: ThreadRecordLookup,
    ): Refinement<Unit, ThreadCatalogStoreFailure> =
        when (previous) {
            ThreadRecordLookup.Absent -> {
                val shard = records.resolve(key.value.take(SHARD_CHARACTERS))
                files.directory(shard)
                files.write(
                    shard.resolve("${key.value}.json"),
                    ThreadRecordDocument.serializer(),
                    ThreadRecordDocument(
                        ThreadRecordDocument.VERSION,
                        key.value,
                        StoredThreadBinding.Current(document),
                    ),
                    maximumBytes = MAXIMUM_RECORD_BYTES,
                )
                Refinement.Refined(Unit)
            }
            is ThreadRecordLookup.Present ->
                when (val existing = previous.binding) {
                    is StoredThreadBinding.Legacy ->
                        Refinement.Rejected(ThreadCatalogStoreFailure.NEW_CONVERSATION_REQUIRED)
                    is StoredThreadBinding.Current ->
                        if (existing.document == document) Refinement.Refined(Unit)
                        else Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_CONFLICT)
                }
        }

    private fun lookup(key: ThreadRecordKey): Refinement<ThreadRecordLookup, ThreadCatalogStoreFailure> {
        files.requireDirectory(records)
        val shard = records.resolve(key.value.take(SHARD_CHARACTERS))
        if (!Files.exists(shard, NOFOLLOW_LINKS)) return Refinement.Refined(ThreadRecordLookup.Absent)
        files.requireDirectory(shard)
        val path = shard.resolve("${key.value}.json")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Refinement.Refined(ThreadRecordLookup.Absent)
        return when (
            val record = files.read(path, ThreadRecordDocument.serializer(), MAXIMUM_RECORD_BYTES).validate(key)
        ) {
            is Refinement.Rejected -> record
            is Refinement.Refined -> Refinement.Refined(ThreadRecordLookup.Present(record.value))
        }
    }

    companion object {
        private const val RECORDS = "records-v3"
        private const val LAYOUT = "layout.json"
        private const val SHARD_CHARACTERS = 2
        private const val MAXIMUM_RECORD_BYTES = 64 * 1024
        private val legacyJson = Json { explicitNulls = false }

        fun open(
            legacy: Path,
            observe: (ThreadMigrationObservation) -> Unit = { System.err.println(it.toJson()) },
        ): FileThreadCatalogStoreOpen {
            val files =
                when (
                    val opened =
                        PrivateRecordFiles.open(legacy.resolveSibling("${legacy.fileName}.d"), ::storageFailure)
                ) {
                    is Refinement.Rejected -> return FileThreadCatalogStoreOpen.Rejected(opened.failure)
                    is Refinement.Refined -> opened.value
                }
            return when (val result = files.locked { openLayout(files, legacy, observe) }) {
                is Refinement.Rejected -> FileThreadCatalogStoreOpen.Rejected(result.failure)
                is Refinement.Refined -> FileThreadCatalogStoreOpen.Opened(FileThreadCatalogStore(files))
            }
        }

        private fun openLayout(
            files: PrivateRecordFiles<ThreadCatalogStoreFailure>,
            legacy: Path,
            observe: (ThreadMigrationObservation) -> Unit,
        ): Refinement<Unit, ThreadCatalogStoreFailure> {
            val marker = files.root.resolve(LAYOUT)
            if (Files.exists(marker, NOFOLLOW_LINKS)) {
                if (
                    files.read(marker, ThreadStoreLayout.serializer(), MAXIMUM_RECORD_BYTES).version !=
                        ThreadRecordDocument.VERSION
                )
                    return Refinement.Rejected(ThreadCatalogStoreFailure.VERSION_UNSUPPORTED)
                files.requireDirectory(files.root.resolve(RECORDS))
                return Refinement.Refined(Unit)
            }
            observe(ThreadMigrationObservation(ThreadMigrationOutcome.Started))
            val result = files.guarded { migrate(files, legacy) }
            observe(
                ThreadMigrationObservation(
                    when (result) {
                        is Refinement.Refined -> ThreadMigrationOutcome.Committed
                        is Refinement.Rejected -> ThreadMigrationOutcome.Rejected(result.failure)
                    }
                )
            )
            return result
        }

        private fun storageFailure(failure: PrivateRecordFailure): ThreadCatalogStoreFailure =
            when (failure) {
                PrivateRecordFailure.STORE_BUSY -> ThreadCatalogStoreFailure.STORE_BUSY
                PrivateRecordFailure.STORE_REJECTED -> ThreadCatalogStoreFailure.STORE_REJECTED
                PrivateRecordFailure.DOCUMENT_MALFORMED -> ThreadCatalogStoreFailure.DOCUMENT_MALFORMED
            }

        private fun legacyRecords(
            source: LegacyThreadStoreDocument
        ): Refinement<List<ThreadRecordDocument>, ThreadCatalogStoreFailure> {
            if (source.version != 2) return Refinement.Rejected(ThreadCatalogStoreFailure.VERSION_UNSUPPORTED)
            val records = linkedMapOf<String, ThreadRecordDocument>()
            for (boundary in source.bindings) {
                val binding =
                    when (val admitted = boundary.admitHistory()) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> admitted.value
                    }
                val key = threadRecordDigest(binding.threadId)
                if (key in records) return Refinement.Rejected(ThreadCatalogStoreFailure.DUPLICATE_THREAD_ID)
                records[key] =
                    ThreadRecordDocument(ThreadRecordDocument.VERSION, key, StoredThreadBinding.Legacy(binding))
            }
            return Refinement.Refined(records.values.toList())
        }

        private fun migrate(
            files: PrivateRecordFiles<ThreadCatalogStoreFailure>,
            legacy: Path,
        ): Refinement<Unit, ThreadCatalogStoreFailure> {
            Files.newDirectoryStream(files.root).use { entries ->
                if (entries.any { it.fileName.toString() != ".lock" })
                    return Refinement.Rejected(ThreadCatalogStoreFailure.MIGRATION_REJECTED)
            }
            val source =
                if (Files.exists(legacy, NOFOLLOW_LINKS))
                    files.read(
                        legacy,
                        LegacyThreadStoreDocument.serializer(),
                        BrokerOperationalLimits.maximumThreadStoreBytes,
                        legacyJson,
                    )
                else LegacyThreadStoreDocument(2, emptyList())
            val records =
                when (val result = legacyRecords(source)) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            val stage = stage(files, records)
            if (
                Files.exists(legacy, NOFOLLOW_LINKS) &&
                    files.read(
                        legacy,
                        LegacyThreadStoreDocument.serializer(),
                        BrokerOperationalLimits.maximumThreadStoreBytes,
                        legacyJson,
                    ) != source
            )
                return Refinement.Rejected(ThreadCatalogStoreFailure.MIGRATION_REJECTED)
            files.publishStage(stage, files.root.resolve(RECORDS))
            files.write(
                files.root.resolve(LAYOUT),
                ThreadStoreLayout.serializer(),
                ThreadStoreLayout(ThreadRecordDocument.VERSION),
                maximumBytes = MAXIMUM_RECORD_BYTES,
            )
            return Refinement.Refined(Unit)
        }

        private fun stage(
            files: PrivateRecordFiles<ThreadCatalogStoreFailure>,
            records: List<ThreadRecordDocument>,
        ): Path {
            val stage = files.createStage()
            for (record in records) {
                val shard = stage.resolve(record.key.take(SHARD_CHARACTERS))
                files.directory(shard)
                val path = shard.resolve("${record.key}.json")
                files.write(path, ThreadRecordDocument.serializer(), record, maximumBytes = MAXIMUM_RECORD_BYTES)
                if (files.read(path, ThreadRecordDocument.serializer(), MAXIMUM_RECORD_BYTES) != record)
                    throw java.io.IOException("Staged thread binding verification failed")
            }
            return stage
        }
    }
}

private sealed interface ThreadRecordLookup {
    data object Absent : ThreadRecordLookup

    data class Present(val binding: StoredThreadBinding) : ThreadRecordLookup
}
