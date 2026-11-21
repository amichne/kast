package io.github.amichne.kast.appserver.runtime

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

        /** The store lives in `<location>.d`; a file at `location` itself is never read. */
        fun open(location: Path): Refinement<InvocationRecords, InvocationFenceFailure> {
            val files =
                when (
                    val admitted =
                        PrivateRecordFiles.open(location.resolveSibling("${location.fileName}.d"), ::storageFailure)
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
                    if (!files.onlyLock()) return@locked Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
                    files.directory(files.root.resolve(RECORDS))
                    files.write(
                        marker,
                        InvocationStoreLayout.serializer(),
                        InvocationStoreLayout(InvocationRecordDocument.VERSION),
                        maximumBytes = MAXIMUM_RECORD_BYTES,
                    )
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
    }
}
