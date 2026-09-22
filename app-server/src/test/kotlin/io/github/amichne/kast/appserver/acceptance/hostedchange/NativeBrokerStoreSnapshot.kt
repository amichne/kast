package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.InvocationPhase
import io.github.amichne.kast.appserver.runtime.InvocationRecordDocument
import io.github.amichne.kast.appserver.runtime.InvocationStoreLayout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Proof that broker replacement retains uncertain invocation records and the existing thread catalog. */
internal class NativeBrokerStoreSnapshot
private constructor(
    val invocationJournalSha256: String,
    val threadStoreSha256: String,
    private val retainedRecords: Map<String, NativeRetainedInvocation>,
) {
    fun requireUnchanged(directory: Path) {
        demand(
            journalDigest(directory) == invocationJournalSha256 && threadDigest(directory) == threadStoreSha256,
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
    }

    fun requireRecordsRetained(directory: Path) {
        val current = records(directory)
        demand(retainedRecords.all { (key, value) -> current[key] == value }, NativeFailure.RESULT_SHAPE_REJECTED)
    }

    fun requireNewUncertainInvocationSince(previous: NativeBrokerStoreSnapshot) {
        demand(
            previous.retainedRecords.all { (key, value) -> retainedRecords[key] == value },
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
        val added = retainedRecords.filterKeys { it !in previous.retainedRecords }
        demand(
            added.size == 1 && added.values.single().phase == InvocationPhase.UNCERTAIN,
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
    }

    companion object {
        fun capture(
            directory: Path,
            expectation: NativeBrokerRetentionExpectation = NativeBrokerRetentionExpectation.UNCERTAIN,
        ): NativeBrokerStoreSnapshot {
            val retained = records(directory)
            demand(retained.isNotEmpty(), NativeFailure.RESULT_SHAPE_REJECTED)
            if (expectation == NativeBrokerRetentionExpectation.UNCERTAIN) {
                demand(
                    retained.values.any { it.phase == InvocationPhase.UNCERTAIN },
                    NativeFailure.RESULT_SHAPE_REJECTED,
                )
            }
            demand(retained.values.none { it.phase == InvocationPhase.STARTED }, NativeFailure.RESULT_SHAPE_REJECTED)
            return NativeBrokerStoreSnapshot(
                invocationJournalSha256 = journalDigest(directory),
                threadStoreSha256 = threadDigest(directory),
                retainedRecords = retained,
            )
        }

        private fun threadDigest(directory: Path): String {
            val store = directory.resolve("threads.json.d")
            val layout = store.resolve("layout.json")
            demand(
                Json.decodeFromString<io.github.amichne.kast.appserver.protocol.ThreadStoreLayout>(
                        Files.readString(layout)
                    )
                    .version == 3,
                NativeFailure.RESULT_SHAPE_REJECTED,
            )
            val records =
                Files.walk(store.resolve("records-v3")).use { paths ->
                    paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted().toList()
                }
            demand(records.all { Files.isRegularFile(it, NOFOLLOW_LINKS) }, NativeFailure.RESULT_SHAPE_REJECTED)
            return sha256(
                (listOf(layout) + records)
                    .joinToString("\n") { path -> "${directory.relativize(path)}:${sha256(Files.readAllBytes(path))}" }
                    .toByteArray()
            )
        }

        private fun journalFiles(directory: Path): List<Path> {
            val store = directory.resolve("invocations.json.d")
            val layout = store.resolve("layout.json")
            demand(
                Json.decodeFromString<InvocationStoreLayout>(Files.readString(layout)).schemaVersion == 2,
                NativeFailure.RESULT_SHAPE_REJECTED,
            )
            val records = store.resolve("records-v2")
            val paths =
                Files.walk(records).use { entries ->
                    entries.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted().toList()
                }
            demand(paths.all { Files.isRegularFile(it, NOFOLLOW_LINKS) }, NativeFailure.RESULT_SHAPE_REJECTED)
            return listOf(layout) + paths
        }

        private fun journalDigest(directory: Path): String =
            sha256(
                journalFiles(directory)
                    .joinToString("\n") { path ->
                        "${directory.relativize(path)}:${sha256(Files.readAllBytes(path))}"
                    }
                    .toByteArray()
            )

        private fun records(directory: Path): Map<String, NativeRetainedInvocation> {
            val files = journalFiles(directory).drop(1)
            val records = files.associate { path ->
                val record = Json.decodeFromString<InvocationRecordDocument>(Files.readString(path))
                demand(
                    record.schemaVersion == 2 &&
                        path.fileName.toString() == "${record.key}.json" &&
                        path.parent.fileName.toString() == record.key.take(2),
                    NativeFailure.RESULT_SHAPE_REJECTED,
                )
                record.key to NativeRetainedInvocation(record.fingerprint, record.phase)
            }
            demand(records.size == files.size, NativeFailure.RESULT_SHAPE_REJECTED)
            return records
        }
    }
}

@Serializable internal data class NativeRetainedInvocation(val fingerprint: String, val phase: InvocationPhase)

internal enum class NativeBrokerRetentionExpectation {
    UNCERTAIN,
    SETTLED,
}
