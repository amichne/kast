package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.InvocationPhase
import java.nio.file.Files
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
            sha256(Files.readAllBytes(directory.resolve("invocations.json"))) == invocationJournalSha256 &&
                sha256(Files.readAllBytes(directory.resolve("threads.json"))) == threadStoreSha256,
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
                invocationJournalSha256 = sha256(Files.readAllBytes(directory.resolve("invocations.json"))),
                threadStoreSha256 = sha256(Files.readAllBytes(directory.resolve("threads.json"))),
                retainedRecords = retained,
            )
        }

        private fun records(directory: Path): Map<String, NativeRetainedInvocation> {
            val journal =
                Json.decodeFromString<NativeRetainedInvocationJournal>(
                    Files.readString(directory.resolve("invocations.json"))
                )
            demand(journal.schemaVersion == 1, NativeFailure.RESULT_SHAPE_REJECTED)
            return journal.records
        }
    }
}

@Serializable
internal data class NativeRetainedInvocationJournal(
    val schemaVersion: Int,
    // Keys are contract-defined dynamic invocation digests; record shape is fixed.
    val records: Map<String, NativeRetainedInvocation>,
)

@Serializable internal data class NativeRetainedInvocation(val fingerprint: String, val phase: InvocationPhase)

internal enum class NativeBrokerRetentionExpectation {
    UNCERTAIN,
    SETTLED,
}
