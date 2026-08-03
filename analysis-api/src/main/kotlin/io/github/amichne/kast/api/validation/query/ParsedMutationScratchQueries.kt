package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.MutationAttemptId
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.query.MutationScratchInspectQuery
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.protocol.ValidationException
import java.nio.file.Path

data class ParsedMutationScratchSet(
    val targetFilePath: NormalizedPath,
    val quarantinePath: NormalizedPath,
    val preparedPath: NormalizedPath,
    val preparedCleanupPath: NormalizedPath,
    val quarantineCleanupPath: NormalizedPath,
    val ownerAttemptId: MutationAttemptId,
    val transitionIndex: Int,
) {
    fun toWire(): MutationScratchSet = MutationScratchSet(
        targetFilePath = targetFilePath.value,
        quarantinePath = quarantinePath.value,
        preparedPath = preparedPath.value,
        preparedCleanupPath = preparedCleanupPath.value,
        quarantineCleanupPath = quarantineCleanupPath.value,
    )

    val ownedPaths: List<NormalizedPath>
        get() = listOf(quarantinePath, preparedPath, preparedCleanupPath, quarantineCleanupPath)
}

@JvmInline
value class ParsedMutationScratchParentPath private constructor(val value: String) {
    fun resolveUnder(workspaceRoot: Path): Path = if (value == ".") {
        workspaceRoot
    } else {
        workspaceRoot.resolve(value).normalize()
    }

    companion object {
        fun parse(value: String): ParsedMutationScratchParentPath {
            require(value.isNotEmpty()) { "Mutation scratch parent path must not be empty" }
            require(value.none(Char::isISOControl) && '\\' !in value) {
                "Mutation scratch parent path must use canonical visible forward-slash text"
            }
            if (value != ".") {
                require(!value.startsWith('/') && !WINDOWS_DRIVE_PREFIX.containsMatchIn(value)) {
                    "Mutation scratch parent path must be workspace-relative"
                }
                require(value.split('/').all { segment ->
                    segment.isNotEmpty() && segment != "." && segment != ".."
                }) { "Mutation scratch parent path must contain only canonical relative components" }
            }
            return ParsedMutationScratchParentPath(value)
        }

        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}

data class ParsedMutationScratchInspectQuery(
    val mutationAttemptId: MutationAttemptId,
    val workspaceRelativeParentPaths: List<ParsedMutationScratchParentPath>,
    val ownedScratchSets: List<ParsedMutationScratchSet>,
)

data class ParsedMutationScratchRecoveryQuery(
    val mutationAttemptId: MutationAttemptId,
    val action: MutationScratchRecoveryAction,
    val scratchDirection: MutationScratchDirection,
    val targetFilePath: NormalizedPath,
    val preimage: MutationScratchRecoveryPreimage,
    val postimage: io.github.amichne.kast.api.contract.ExactByteImage,
    val scratch: ParsedMutationScratchSet,
)

fun MutationScratchInspectQuery.parsed(): ParsedMutationScratchInspectQuery = scratchValidationBoundary {
    val attemptId = MutationAttemptId.parse(mutationAttemptId)
    require(workspaceRelativeParentPaths.isNotEmpty()) {
        "Mutation scratch inspection requires at least one parent path"
    }
    require(workspaceRelativeParentPaths == workspaceRelativeParentPaths.sorted().distinct()) {
        "Mutation scratch parent paths must be sorted and unique"
    }
    val scratchKeys = ownedScratchSets.map(MutationScratchSet::sortKey)
    require(scratchKeys == scratchKeys.sorted() && scratchKeys.distinct().size == scratchKeys.size) {
        "Owned mutation scratch sets must be sorted uniquely by target and all role paths"
    }
    require(ownedScratchSets.flatMap(MutationScratchSet::ownedPathStrings).let { paths ->
        paths.distinct().size == paths.size
    }) { "Owned mutation scratch role paths must be unique across all sets" }
    ParsedMutationScratchInspectQuery(
        mutationAttemptId = attemptId,
        workspaceRelativeParentPaths = workspaceRelativeParentPaths.map(ParsedMutationScratchParentPath::parse),
        ownedScratchSets = ownedScratchSets.map { scratch -> scratch.parsed() },
    )
}

fun MutationScratchRecoveryQuery.parsed(): ParsedMutationScratchRecoveryQuery = scratchValidationBoundary {
    val attemptId = MutationAttemptId.parse(mutationAttemptId)
    val target = normalizedExactPath(targetFilePath)
    val parsedScratch = scratch.parsed(expectedTarget = target)
    ParsedMutationScratchRecoveryQuery(
        mutationAttemptId = attemptId,
        action = action,
        scratchDirection = scratchDirection,
        targetFilePath = target,
        preimage = preimage,
        postimage = postimage,
        scratch = parsedScratch,
    )
}

internal fun MutationScratchSet.parsed(
    expectedOwnerAttemptId: MutationAttemptId? = null,
    expectedTarget: NormalizedPath? = null,
): ParsedMutationScratchSet {
    val target = normalizedExactPath(targetFilePath)
    require(expectedTarget == null || target == expectedTarget) {
        "Mutation scratch target does not match the authorized transition target"
    }
    val quarantine = normalizedExactPath(quarantinePath)
    val prepared = normalizedExactPath(preparedPath)
    val preparedCleanup = normalizedExactPath(preparedCleanupPath)
    val quarantineCleanup = normalizedExactPath(quarantineCleanupPath)
    val quarantineName = quarantine.toJavaPath().fileName.toString()
    val namespace = quarantineName.removePrefix(MutationScratchSet.QUARANTINE_PREFIX)
    require(namespace.length > MUTATION_ATTEMPT_ID_LENGTH && namespace[MUTATION_ATTEMPT_ID_LENGTH] == '-') {
        "Mutation quarantine name must encode one owner attempt and transition"
    }
    val ownerAttemptId = MutationAttemptId.parse(namespace.take(MUTATION_ATTEMPT_ID_LENGTH))
    require(expectedOwnerAttemptId == null || ownerAttemptId == expectedOwnerAttemptId) {
        "Mutation scratch owner attempt does not match the active verified attempt"
    }
    val rawIndex = namespace.drop(MUTATION_ATTEMPT_ID_LENGTH + 1)
    val transitionIndex = rawIndex.toIntOrNull()
        ?.takeIf { index -> index >= 0 && index.toString() == rawIndex }
        ?: throw IllegalArgumentException("Mutation scratch transition index must be canonical and nonnegative")
    require(
        prepared.toJavaPath().fileName.toString() ==
            "${MutationScratchSet.PREPARED_PREFIX}${ownerAttemptId.value}-$transitionIndex${MutationScratchSet.PREPARED_SUFFIX}",
    ) { "Mutation prepared name does not match its attempt and transition" }
    require(
        preparedCleanup.toJavaPath().fileName.toString() ==
            "${MutationScratchSet.CLEANUP_PREFIX}${ownerAttemptId.value}-$transitionIndex-prepared",
    ) { "Prepared cleanup name does not match its attempt and transition" }
    require(
        quarantineCleanup.toJavaPath().fileName.toString() ==
            "${MutationScratchSet.CLEANUP_PREFIX}${ownerAttemptId.value}-$transitionIndex-quarantine",
    ) { "Quarantine cleanup name does not match its attempt and transition" }
    return ParsedMutationScratchSet(
        targetFilePath = target,
        quarantinePath = quarantine,
        preparedPath = prepared,
        preparedCleanupPath = preparedCleanup,
        quarantineCleanupPath = quarantineCleanup,
        ownerAttemptId = ownerAttemptId,
        transitionIndex = transitionIndex,
    )
}

private fun MutationScratchSet.sortKey(): String = listOf(
    targetFilePath,
    quarantinePath,
    preparedPath,
    preparedCleanupPath,
    quarantineCleanupPath,
).joinToString("\u0000")

private fun MutationScratchSet.ownedPathStrings(): List<String> = listOf(
    quarantinePath,
    preparedPath,
    preparedCleanupPath,
    quarantineCleanupPath,
)

private const val MUTATION_ATTEMPT_ID_LENGTH = 36

private fun normalizedExactPath(value: String): NormalizedPath {
    val path = Path.of(value)
    require(path.isAbsolute && path.normalize().toString() == value) {
        "Mutation scratch paths must be normalized and absolute"
    }
    return NormalizedPath.parse(value)
}

private inline fun <T> scratchValidationBoundary(block: () -> T): T = try {
    block()
} catch (failure: ValidationException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw ValidationException(failure.message ?: "Invalid mutation scratch request")
}
