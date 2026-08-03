package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class RepositorySourceStateAdmission(
    private val repository: RepositoryAdmissionRepository,
    private val stabilityCheckpoint: SourceStateStabilityCheckpoint,
    private val contentReadCheckpoint: SourceContentReadCheckpoint,
    private val nanoTime: () -> Long,
) {
    fun parse(
        input: RawSourceStateInput,
        root: CanonicalRepositoryRoot,
        compilationUnits: List<AdmittedCompilationUnit>,
        resourceBounds: EstablishedResourceBounds,
    ): ExactSourceState {
        val revision = input.revision
            ?.takeIf { raw -> raw.matches(EXACT_REVISION) }
            ?.lowercase()
            ?: reject(RepositoryOperationRejection.SourceRevisionUnresolvable(input.revision))
        val resolvedRevision = gitOutput(
            Path.of(root.value),
            "rev-parse",
            "--verify",
            "--end-of-options",
            "$revision^{commit}",
        )?.lowercase()
        if (resolvedRevision != revision) {
            reject(RepositoryOperationRejection.SourceRevisionUnresolvable(input.revision))
        }
        val rawInputs = input.inputs
            ?: reject(
                RepositoryOperationRejection.SourceStateEvidenceMissing(
                    evidence = SourceStateEvidenceKind.INVENTORY,
                    path = null,
                ),
            )
        val admittedByPath = linkedMapOf<String, ExactSourceInput>()
        rawInputs.forEach { rawInput ->
            val rawPath = rawInput.path?.takeIf(String::isNotBlank)
                ?: reject(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.PATH,
                        rawInput.path,
                    ),
                )
            val kind = rawInput.kind
                ?: reject(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.KIND,
                        rawPath,
                    ),
                )
            val presence = rawInput.presence
                ?: reject(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.PRESENCE,
                        rawPath,
                    ),
                )
            val disposition = rawInput.disposition
                ?: reject(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.DISPOSITION,
                        rawPath,
                    ),
                )
            val path = repository.parsePath(root, rawPath)
            val digest = when {
                presence == RawSourceInputPresence.DELETED -> {
                    if (kind != RawSourceInputKind.TRACKED_CHANGE || rawInput.contentSha256 != null) {
                        reject(RepositoryOperationRejection.SourceStateConflict(rawPath))
                    }
                    null
                }

                disposition == RawSourceInputDisposition.INCLUDED -> rawInput.contentSha256
                    ?.takeIf { raw -> raw.matches(SHA_256) }
                    ?.lowercase()
                    ?.let(SourceContentDigest::fromValidated)
                    ?: reject(
                        RepositoryOperationRejection.SourceStateEvidenceMissing(
                            SourceStateEvidenceKind.CONTENT_DIGEST,
                            rawPath,
                        ),
                    )

                else -> {
                    if (rawInput.contentSha256 != null) {
                        reject(RepositoryOperationRejection.SourceStateConflict(rawPath))
                    }
                    null
                }
            }
            val admitted = ExactSourceInput.create(
                path = path,
                kind = kind,
                presence = presence,
                disposition = disposition,
                contentDigest = digest,
            )
            val previous = admittedByPath[path.value]
            if (previous != null && !previous.sameEvidenceAs(admitted)) {
                reject(RepositoryOperationRejection.SourceStateConflict(rawPath))
            }
            admittedByPath[path.value] = admitted
        }
        val admittedInputs = admittedByPath.values.sortedBy { sourceInput -> sourceInput.path.value }
        validateAgainstRepository(root, revision, admittedInputs, compilationUnits, resourceBounds)
        return ExactSourceState.create(
            revision = SourceRevision.fromValidated(revision),
            inputs = admittedInputs,
        )
    }

    private fun validateAgainstRepository(
        root: CanonicalRepositoryRoot,
        revision: String,
        inputs: List<ExactSourceInput>,
        compilationUnits: List<AdmittedCompilationUnit>,
        resourceBounds: EstablishedResourceBounds,
    ) {
        val deadlineNanos = deadlineAfter(resourceBounds.timeLimitMillis.value, nanoTime)
        validateObservation(root, revision, inputs, compilationUnits, resourceBounds, deadlineNanos)
        stabilityCheckpoint.afterInitialValidation()
        validateObservation(root, revision, inputs, compilationUnits, resourceBounds, deadlineNanos)
    }

    private fun validateObservation(
        root: CanonicalRepositoryRoot,
        revision: String,
        inputs: List<ExactSourceInput>,
        compilationUnits: List<AdmittedCompilationUnit>,
        resourceBounds: EstablishedResourceBounds,
        deadlineNanos: Long,
    ) {
        val rootPath = Path.of(root.value)
        if (remainingMillis(deadlineNanos, nanoTime) == null) {
            reject(RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME))
        }
        val headRevision = gitOutput(rootPath, "rev-parse", "HEAD")?.lowercase()
        if (remainingMillis(deadlineNanos, nanoTime) == null) {
            reject(RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME))
        }
        if (headRevision != revision) {
            reject(RepositoryOperationRejection.SourceRevisionUnresolvable(revision))
        }
        val inventoryBudget = SourceInventoryBudget(
            memoryLimitBytes = resourceBounds.memoryLimitBytes.value,
            pathLimit = resourceBounds.pathLimit.value,
        )
        val indexRecords = requiredGitInventoryPaths(
            rootPath,
            inventoryBudget,
            deadlineNanos,
            nanoTime,
            "ls-files",
            "-v",
            "-z",
            "--",
        )
        indexRecords.forEach { record ->
            if (record.length < GIT_INDEX_RECORD_PREFIX_LENGTH || record[1] != ' ') {
                reject(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.INVENTORY,
                        null,
                    ),
                )
            }
            val tag = record.first()
            if (tag == GIT_SKIP_WORKTREE_TAG || tag.isLowerCase()) {
                val rawPath = record.substring(GIT_INDEX_RECORD_PREFIX_LENGTH)
                val path = repository.parsePath(root, rawPath)
                reject(RepositoryOperationRejection.SourceStateConflict(path.value))
            }
        }
        val trackedPaths = requiredGitInventoryPaths(
            rootPath,
            inventoryBudget,
            deadlineNanos,
            nanoTime,
            "diff",
            "--name-only",
            "--no-renames",
            "-z",
            revision,
            "--",
        )
        val untrackedPaths = requiredGitInventoryPaths(
            rootPath,
            inventoryBudget,
            deadlineNanos,
            nanoTime,
            "ls-files",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
        )
        val sourceRoots = compilationUnits
            .flatMap { unit -> unit.sourceRoots }
            .distinct()
            .sortedBy(RepositoryRelativePath::value)
        val generatedSourceRoots = compilationUnits
            .flatMap { unit -> unit.generatedSourceRoots }
            .distinct()
            .sortedBy(RepositoryRelativePath::value)
        val ignoredGeneratedPaths = requiredGitInventoryPaths(
            rootPath,
            inventoryBudget,
            deadlineNanos,
            nanoTime,
            *buildList {
                add("ls-files")
                add("--others")
                add("--ignored")
                add("--exclude-standard")
                add("-z")
                add("--")
                addAll(sourceRoots.map(RepositoryRelativePath::value))
            }.toTypedArray(),
        )
        val actualByPath = linkedMapOf<String, LiveSourceKind>()
        trackedPaths.forEach { rawPath ->
            val path = repository.parsePath(root, rawPath)
            actualByPath[path.value] = LiveSourceKind.TRACKED_CHANGE
        }
        untrackedPaths.forEach { rawPath ->
            val path = repository.parsePath(root, rawPath)
            val kind = if (generatedSourceRoots.any { generatedRoot -> path.isWithin(generatedRoot) }) {
                LiveSourceKind.GENERATED
            } else {
                LiveSourceKind.UNTRACKED
            }
            actualByPath.putIfAbsent(path.value, kind)
        }
        ignoredGeneratedPaths.forEach { rawPath ->
            val path = repository.parsePath(root, rawPath)
            val kind = if (generatedSourceRoots.any { generatedRoot -> path.isWithin(generatedRoot) }) {
                LiveSourceKind.GENERATED
            } else {
                LiveSourceKind.UNTRACKED
            }
            actualByPath.putIfAbsent(path.value, kind)
        }
        if (actualByPath.size > resourceBounds.pathLimit.value) {
            reject(RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.PATHS))
        }
        val admittedByPath = inputs.associateBy { input -> input.path.value }
        actualByPath.forEach { (path, liveKind) ->
            val evidence = admittedByPath[path]
                ?: reject(RepositoryOperationRejection.SourceStateConflict(path))
            val livePresence = if (Files.exists(rootPath.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                RawSourceInputPresence.PRESENT
            } else {
                RawSourceInputPresence.DELETED
            }
            if (evidence.presence != livePresence || !liveKind.accepts(evidence.kind)) {
                reject(RepositoryOperationRejection.SourceStateConflict(path))
            }
        }
        inputs.forEach { evidence ->
            val liveKind = actualByPath[evidence.path.value]
            val classificationMatches = when (evidence.kind) {
                RawSourceInputKind.TRACKED_CHANGE -> liveKind == LiveSourceKind.TRACKED_CHANGE
                RawSourceInputKind.UNTRACKED -> liveKind == LiveSourceKind.UNTRACKED
                RawSourceInputKind.GENERATED -> liveKind == LiveSourceKind.GENERATED
            }
            if (!classificationMatches) {
                reject(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
            }
            val sourcePath = rootPath.resolve(evidence.path.value)
            when (evidence) {
                is ExactSourceInput.IncludedFile -> {
                    contentReadCheckpoint.beforeContentRead()
                    val liveDigest = when (
                        val digest = sha256(
                            rootPath,
                            evidence.path,
                            resourceBounds.memoryLimitBytes.value,
                            deadlineNanos,
                            nanoTime,
                        )
                    ) {
                        is ContentDigestResult.Success -> digest.value
                        ContentDigestResult.TimeExceeded -> reject(
                            RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME),
                        )
                        ContentDigestResult.Unavailable -> reject(
                            RepositoryOperationRejection.SourceStateConflict(evidence.path.value),
                        )
                    }
                    if (liveDigest != evidence.contentDigest.value) {
                        reject(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
                    }
                }

                is ExactSourceInput.ExcludedFile -> if (!Files.exists(
                    sourcePath,
                    LinkOption.NOFOLLOW_LINKS,
                )) {
                    reject(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
                }

                is ExactSourceInput.DeletedTrackedInput -> if (Files.exists(
                    sourcePath,
                    LinkOption.NOFOLLOW_LINKS,
                )) {
                    reject(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
                }
            }
        }
    }

    private fun ExactSourceInput.sameEvidenceAs(other: ExactSourceInput): Boolean =
        path == other.path &&
            kind == other.kind &&
            presence == other.presence &&
            disposition == other.disposition &&
            contentDigest == other.contentDigest

    private enum class LiveSourceKind {
        TRACKED_CHANGE,
        UNTRACKED,
        GENERATED;

        fun accepts(kind: RawSourceInputKind): Boolean = when (this) {
            TRACKED_CHANGE -> kind == RawSourceInputKind.TRACKED_CHANGE
            UNTRACKED -> kind == RawSourceInputKind.UNTRACKED
            GENERATED -> kind == RawSourceInputKind.GENERATED
        }
    }
}

internal fun interface SourceStateStabilityCheckpoint {
    fun afterInitialValidation()

    companion object {
        val NO_OP: SourceStateStabilityCheckpoint = SourceStateStabilityCheckpoint {}
    }
}

internal fun interface SourceContentReadCheckpoint {
    fun beforeContentRead()

    companion object {
        val NO_OP: SourceContentReadCheckpoint = SourceContentReadCheckpoint {}
    }
}
