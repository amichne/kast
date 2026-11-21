package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.storage.PrivateRecordFailure
import io.github.amichne.kast.appserver.storage.PrivateRecordFiles
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        when (val admitted = binding.document.admit()) {
            is Refinement.Rejected -> ThreadStoreRead.Rejected(admitted.failure)
            is Refinement.Refined -> ThreadStoreRead.Found(admitted.value)
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
                if (previous.binding.document == document) Refinement.Refined(Unit)
                else Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_CONFLICT)
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

        /** The store lives in `<location>.d`; a file at `location` itself is never read. */
        fun open(location: Path): FileThreadCatalogStoreOpen {
            val files =
                when (
                    val opened =
                        PrivateRecordFiles.open(location.resolveSibling("${location.fileName}.d"), ::storageFailure)
                ) {
                    is Refinement.Rejected -> return FileThreadCatalogStoreOpen.Rejected(opened.failure)
                    is Refinement.Refined -> opened.value
                }
            return when (val result = files.locked { openLayout(files) }) {
                is Refinement.Rejected -> FileThreadCatalogStoreOpen.Rejected(result.failure)
                is Refinement.Refined -> FileThreadCatalogStoreOpen.Opened(FileThreadCatalogStore(files))
            }
        }

        private fun openLayout(
            files: PrivateRecordFiles<ThreadCatalogStoreFailure>
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
            if (!files.onlyLock()) return Refinement.Rejected(ThreadCatalogStoreFailure.STORE_REJECTED)
            files.directory(files.root.resolve(RECORDS))
            files.write(
                marker,
                ThreadStoreLayout.serializer(),
                ThreadStoreLayout(ThreadRecordDocument.VERSION),
                maximumBytes = MAXIMUM_RECORD_BYTES,
            )
            return Refinement.Refined(Unit)
        }

        private fun storageFailure(failure: PrivateRecordFailure): ThreadCatalogStoreFailure =
            when (failure) {
                PrivateRecordFailure.STORE_BUSY -> ThreadCatalogStoreFailure.STORE_BUSY
                PrivateRecordFailure.STORE_REJECTED -> ThreadCatalogStoreFailure.STORE_REJECTED
                PrivateRecordFailure.DOCUMENT_MALFORMED -> ThreadCatalogStoreFailure.DOCUMENT_MALFORMED
            }
    }
}

private sealed interface ThreadRecordLookup {
    data object Absent : ThreadRecordLookup

    data class Present(val binding: StoredThreadBinding) : ThreadRecordLookup
}
