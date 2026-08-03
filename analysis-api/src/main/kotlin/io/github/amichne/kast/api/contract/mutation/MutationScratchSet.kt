package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
data class MutationScratchSet(
    @DocField(description = "Normalized absolute mutation target path.")
    val targetFilePath: String,
    @DocField(description = "Predeclared same-parent quarantine path for the exact target preimage.")
    val quarantinePath: String,
    @DocField(description = "Predeclared same-parent prepared path for the exact target postimage.")
    val preparedPath: String,
    @DocField(description = "Predeclared same-parent cleanup path for the prepared entry.")
    val preparedCleanupPath: String,
    @DocField(description = "Predeclared same-parent cleanup path for the quarantined preimage.")
    val quarantineCleanupPath: String,
) {
    init {
        val paths = listOf(
            targetFilePath,
            quarantinePath,
            preparedPath,
            preparedCleanupPath,
            quarantineCleanupPath,
        )
        require(paths.all(::isNormalizedAbsoluteExactFileImagePath)) {
            "Mutation scratch paths must be normalized and absolute"
        }
        require(paths.map(String::toPath).map(Path::getParent).distinct().size == 1) {
            "Mutation scratch paths must share the exact target parent"
        }
        require(paths.distinct().size == paths.size) {
            "Mutation scratch paths must be pairwise unique"
        }
        require(quarantinePath.toPath().fileName.toString().startsWith(QUARANTINE_PREFIX)) {
            "Mutation quarantine path must use the closed quarantine prefix"
        }
        require(
            preparedPath.toPath().fileName.toString().let { name ->
                name.startsWith(PREPARED_PREFIX) && name.endsWith(PREPARED_SUFFIX)
            },
        ) { "Mutation prepared path must use the closed prepared shape" }
        require(
            listOf(preparedCleanupPath, quarantineCleanupPath).all { path ->
                path.toPath().fileName.toString().startsWith(CLEANUP_PREFIX)
            },
        ) { "Mutation cleanup paths must use the closed cleanup prefix" }
    }

    companion object {
        const val QUARANTINE_PREFIX: String = ".kast-quarantine-"
        const val PREPARED_PREFIX: String = ".kast-prepared-"
        const val PREPARED_SUFFIX: String = ".tmp"
        const val CLEANUP_PREFIX: String = ".kast-cleanup-"
    }
}

private fun String.toPath(): Path = Path.of(this)
