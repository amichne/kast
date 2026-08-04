package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

internal data class SemanticGraphStageInput(
    val sourcePath: SemanticGraphSourcePath,
    val contentHash: FileContentHash,
)

internal fun semanticGraphContentHash(path: WorkspaceSourcePath): FileContentHash =
    FileContentHash.parse(FileHashing.sha256(Files.readAllBytes(path.absolute.value.toJavaPath())))

internal fun semanticGraphStageInputFingerprint(
    inputs: List<SemanticGraphStageInput>,
): FileStageInputFingerprint = FileStageInputFingerprint.parse(
    sha256(
        buildString {
            inputs.sortedBy(SemanticGraphStageInput::sourcePath).forEach { input ->
                append("source:")
                    .append(input.sourcePath.value)
                    .append(':')
                    .append(input.contentHash.value)
                    .append('\n')
            }
        },
    ).value,
)

internal fun semanticGraphScopeFingerprint(
    selectedPaths: List<SemanticGraphSourcePath>,
    removedPaths: List<SemanticGraphSourcePath>,
): SemanticGraphSha256 = sha256(
    buildString {
        selectedPaths.sorted().forEach { append("selected:").append(it.value).append('\n') }
        removedPaths.sorted().forEach { append("removed:").append(it.value).append('\n') }
    },
)

private fun sha256(value: String): SemanticGraphSha256 = sha256(value.toByteArray(StandardCharsets.UTF_8))

private fun sha256(value: ByteArray): SemanticGraphSha256 = SemanticGraphSha256.parse(
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) },
)
