package io.github.amichne.kast.cli

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

/** Ordered, bounded effect stages for one optional IntelliJ index seed. */
enum class IndexSeedStage {
    REQUEST_VALIDATION,
    SOURCE_DISCOVERY,
    SOURCE_QUIESCENCE,
    COPY_ADMISSION,
    COPY,
    SOURCE_STABILITY,
    CLONE_VALIDATION,
    RECEIPT_PUBLICATION,
    PUBLICATION_QUIESCENCE,
    CACHE_PUBLICATION,
}

/** Payload-free lifecycle evidence emitted at the seed coordinator boundary. */
sealed interface IndexSeedActivity {
    val stage: IndexSeedStage

    data class Started(
        override val stage: IndexSeedStage,
    ) : IndexSeedActivity

    data class Completed(
        override val stage: IndexSeedStage,
    ) : IndexSeedActivity

    data class Rejected(
        override val stage: IndexSeedStage,
        val failure: IndexSeedFailure,
    ) : IndexSeedActivity
}

enum class IndexSeedActivityPublication {
    PUBLISHED,
    SKIPPED,
    REJECTED,
}

fun interface IndexSeedActivitySink {
    fun publish(activity: IndexSeedActivity): IndexSeedActivityPublication

    data object Disabled : IndexSeedActivitySink {
        override fun publish(activity: IndexSeedActivity): IndexSeedActivityPublication =
            IndexSeedActivityPublication.SKIPPED
    }
}

/** Synchronized structured progress for a foreground `kast start --seed-from-idea`. */
class JsonLineIndexSeedActivitySink(
    private val output: PrintStream,
) : IndexSeedActivitySink {
    @Synchronized
    override fun publish(activity: IndexSeedActivity): IndexSeedActivityPublication {
        val document = buildJsonObject {
            put("component", "kast-cli")
            put("event", "index-seed-stage")
            put("stage", activity.stage.wireName())
            put(
                "outcome",
                when (activity) {
                    is IndexSeedActivity.Started -> "started"
                    is IndexSeedActivity.Completed -> "completed"
                    is IndexSeedActivity.Rejected -> "rejected"
                },
            )
            if (activity is IndexSeedActivity.Rejected) {
                put("reason", activity.failure.activityReason())
            }
        }
        output.println(document.toString())
        return if (output.checkError()) {
            IndexSeedActivityPublication.REJECTED
        } else {
            IndexSeedActivityPublication.PUBLISHED
        }
    }
}

private fun IndexSeedStage.wireName(): String = name.lowercase().replace('_', '-')

private fun IndexSeedFailure.activityReason(): String = when (this) {
    is IndexSeedFailure.Incompatibility -> "incompatibility"
    IndexSeedFailure.Ambiguity -> "ambiguity"
    IndexSeedFailure.MissingInstallation -> "missing-installation"
    IndexSeedFailure.RunningSourceIde -> "running-source-ide"
    IndexSeedFailure.ConsentAbsent -> "consent-absent"
    IndexSeedFailure.UnsupportedFilesystem -> "unsupported-filesystem"
    IndexSeedFailure.SourceMutation -> "source-mutation"
    IndexSeedFailure.CopyFailure -> "copy-failure"
    IndexSeedFailure.ValidationFailure -> "validation-failure"
}
