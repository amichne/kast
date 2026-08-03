package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class RepositoryOperationAdmissionParser(
    private val rawInput: RawRepositoryOperationInput,
    private val stabilityCheckpoint: SourceStateStabilityCheckpoint = SourceStateStabilityCheckpoint.NO_OP,
    private val contentReadCheckpoint: SourceContentReadCheckpoint = SourceContentReadCheckpoint.NO_OP,
    private val authorityReadCheckpoint: GitAuthorityReadCheckpoint = GitAuthorityReadCheckpoint.NO_OP,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun parse(): RepositoryOperationAdmission.Result = try {
        val rawBounds = rawInput.resourceBounds?.copy()
            ?: fail(RepositoryOperationRejection.ResourceBoundMissing(ResourceBoundKind.TIME))
        val resourceBounds = parseResourceBounds(rawBounds)
        parseAdmitted(snapshotRawInput(rawInput), resourceBounds)
    } catch (rejected: AdmissionParseRejected) {
        RepositoryOperationAdmission.Result.Rejected(rejected.rejection)
    }

    private fun snapshotRawInput(input: RawRepositoryOperationInput): RawRepositoryOperationInput = input.copy(
        repository = input.repository?.copy(),
        sourceState = input.sourceState?.let { sourceState ->
            sourceState.copy(
                inputs = snapshotList<RawSourceInput>(
                    sourceState.inputs,
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.INVENTORY,
                        null,
                    ),
                )?.map(RawSourceInput::copy),
            )
        },
        buildOwnership = when (val buildOwnership = input.buildOwnership) {
            null -> null
            RawBuildOwnershipEvidence.Unavailable -> RawBuildOwnershipEvidence.Unavailable
            is RawBuildOwnershipEvidence.Available -> buildOwnership.copy(
                compilationUnits = snapshotList<RawCompilationUnitInput>(
                    buildOwnership.compilationUnits,
                    incomplete(SemanticConfigurationField.COMPILATION_UNITS, null),
                )?.map(::snapshotCompilationUnit),
            )
        },
        scope = snapshotList<RawScopeSelector>(
            input.scope,
            RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SCOPE),
        )?.map { selector ->
            when (selector) {
                is RawScopeSelector.Module -> selector.copy()
                is RawScopeSelector.SourceSet -> selector.copy()
                is RawScopeSelector.Declaration -> selector.copy()
                is RawScopeSelector.Family -> selector.copy()
            }
        },
        resourceBounds = input.resourceBounds?.copy(),
    )

    private fun snapshotCompilationUnit(input: RawCompilationUnitInput): RawCompilationUnitInput {
        val ownerId = input.ownerId
        return input.copy(
            sourceRoots = snapshotSet<String>(
                input.sourceRoots,
                incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId),
            ),
            generatedSourceRoots = snapshotSet<String>(
                input.generatedSourceRoots,
                incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId),
            ),
            declarations = snapshotList<RawOwnedDeclarationInput>(
                input.declarations,
                incomplete(SemanticConfigurationField.DECLARATIONS, ownerId),
            )?.map(RawOwnedDeclarationInput::copy),
            families = snapshotSet<String>(
                input.families,
                incomplete(SemanticConfigurationField.FAMILIES, ownerId),
            ),
            sourceSetRelationships = snapshotSet<RawSourceSetRelationshipInput>(
                input.sourceSetRelationships,
                incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, ownerId),
            )?.map(RawSourceSetRelationshipInput::copy)?.toSet(),
            compiler = input.compiler?.let { compiler -> snapshotCompiler(compiler, ownerId) },
        )
    }

    private fun snapshotCompiler(
        input: RawCompilerInput,
        ownerId: String?,
    ): RawCompilerInput = input.copy(
        languageSettings = snapshotMap<String, String>(
            input.languageSettings,
            incomplete(SemanticConfigurationField.LANGUAGE_SETTINGS, ownerId),
        ),
        compilerImplementation = input.compilerImplementation?.copy(),
        toolchain = input.toolchain?.copy(),
        compilerOptions = snapshotList<RawCompilerOptionInput>(
            input.compilerOptions,
            incomplete(SemanticConfigurationField.COMPILER_OPTIONS, ownerId),
        )?.map(RawCompilerOptionInput::copy),
        resolvedDependencies = snapshotList<RawResolvedArtifactInput>(
            input.resolvedDependencies,
            incomplete(SemanticConfigurationField.DEPENDENCIES, ownerId),
        )?.map(RawResolvedArtifactInput::copy),
        compilerPlugins = snapshotList<RawCompilerPluginInput>(
            input.compilerPlugins,
            incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId),
        )?.map { plugin ->
            plugin.copy(
                classpath = snapshotList<RawResolvedArtifactInput>(
                    plugin.classpath,
                    incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId),
                )?.map(RawResolvedArtifactInput::copy),
                options = snapshotList<RawCompilerOptionInput>(
                    plugin.options,
                    incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId),
                )?.map(RawCompilerOptionInput::copy),
            )
        },
    )

    private inline fun <reified Value : Any> snapshotList(
        values: List<*>?,
        rejection: RepositoryOperationRejection,
    ): List<Value>? = values?.let { source ->
        snapshotCollection(rejection) {
            val expectedSize = source.size
            val result = ArrayList<Value>(expectedSize)
            repeat(expectedSize) { index ->
                val value = source[index] as? Value ?: fail(rejection)
                result.add(value)
            }
            if (source.size != expectedSize) fail(rejection)
            result
        }
    }

    private inline fun <reified Value : Any> snapshotSet(
        values: Set<*>?,
        rejection: RepositoryOperationRejection,
    ): Set<Value>? = values?.let { source ->
        snapshotCollection(rejection) {
            val expectedSize = source.size
            val result = LinkedHashSet<Value>(expectedSize)
            source.forEach { rawValue ->
                val value = rawValue as? Value ?: fail(rejection)
                result.add(value)
            }
            if (source.size != expectedSize || result.size != expectedSize) fail(rejection)
            result
        }
    }

    private inline fun <reified Key : Any, reified Value : Any> snapshotMap(
        values: Map<*, *>?,
        rejection: RepositoryOperationRejection,
    ): Map<Key, Value>? = values?.let { source ->
        snapshotCollection(rejection) {
            val expectedSize = source.size
            val result = LinkedHashMap<Key, Value>(expectedSize)
            source.entries.forEach { entry ->
                val key = entry.key as? Key ?: fail(rejection)
                val value = entry.value as? Value ?: fail(rejection)
                result[key] = value
            }
            if (source.size != expectedSize || result.size != expectedSize) fail(rejection)
            result
        }
    }

    private inline fun <Value> snapshotCollection(
        rejection: RepositoryOperationRejection,
        snapshot: () -> Value,
    ): Value = try {
        snapshot()
    } catch (rejected: AdmissionParseRejected) {
        throw rejected
    } catch (_: RuntimeException) {
        fail(rejection)
    }

    private fun parseAdmitted(
        input: RawRepositoryOperationInput,
        resourceBounds: EstablishedResourceBounds,
    ): RepositoryOperationAdmission.Result.Admitted {
        val rawRepository = input.repository
            ?: fail(
                RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.REPOSITORY),
            )
        val rawSourceState = input.sourceState
            ?: fail(
                RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SOURCE_STATE),
            )
        val rawBuildOwnership = input.buildOwnership
            ?: fail(
                RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.BUILD_OWNERSHIP),
            )
        val rawScope = input.scope
            ?: fail(
                RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SCOPE),
            )
        val canonicalRoot = parseRoot(rawRepository)
        val compilationUnits = parseCompilationUnits(rawBuildOwnership, canonicalRoot)
        val sourceState = parseSourceState(
            rawSourceState,
            canonicalRoot,
            compilationUnits,
            resourceBounds,
        )
        val parsedScope = parseScope(rawScope, compilationUnits)
        val repositoryState = AdmittedRepositoryState.create(
            canonicalRoot = canonicalRoot,
            sourceState = sourceState,
            semanticConfiguration = parsedScope.semanticConfiguration,
            compilationUnits = compilationUnits,
        )
        return RepositoryOperationAdmission.Result.Admitted(
            AdmittedRepositoryOperation.create(
                repositoryState = repositoryState,
                resolvedScope = parsedScope.scope,
                resourceBounds = resourceBounds,
            ),
        )
    }

    private fun parseRoot(input: RawRepositoryInput): CanonicalRepositoryRoot {
        val requestedRoot = input.requestedRoot?.takeIf(String::isNotBlank)
            ?: return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(input.requestedRoot))
        val requestedPath = runCatching { Path.of(requestedRoot) }.getOrNull()
            ?: return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        val candidate = if (requestedPath.isAbsolute) {
            requestedPath
        } else {
            val base = input.baseDirectory
                ?.takeIf(String::isNotBlank)
                ?.let { raw -> runCatching { Path.of(raw) }.getOrNull() }
                ?: return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
            base.resolve(requestedPath)
        }.normalize()
        if (!Files.isDirectory(candidate)) {
            return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        val canonical = runCatching { candidate.toRealPath().normalize() }.getOrNull()
            ?: return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        if (!Files.exists(canonical.resolve(".git"))) {
            return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        val topLevel = gitOutput(canonical, "rev-parse", "--show-toplevel")
            ?.let { raw -> runCatching { Path.of(raw).toRealPath().normalize() }.getOrNull() }
        if (topLevel != canonical) {
            return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        if (!hasRegisteredGitAuthority(canonical)) {
            return fail(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        return CanonicalRepositoryRoot.fromValidated(canonical.toString())
    }

    private fun hasRegisteredGitAuthority(root: Path): Boolean {
        val dotGit = root.resolve(".git")
        val attributes = runCatching {
            Files.readAttributes(dotGit, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return false
        if (attributes.isSymbolicLink) return false
        val actualGitDirectory = gitOutput(
            root,
            "rev-parse",
            "--path-format=absolute",
            "--absolute-git-dir",
        )?.let(::canonicalExistingPath) ?: return false
        val commonGitDirectory = gitOutput(
            root,
            "rev-parse",
            "--path-format=absolute",
            "--git-common-dir",
        )?.let(::canonicalExistingPath) ?: return false
        if (attributes.isDirectory) {
            return canonicalExistingPath(dotGit.toString()) == actualGitDirectory &&
                actualGitDirectory == commonGitDirectory
        }
        if (!attributes.isRegularFile) return false
        val declaredGitDirectory = readGitDirectoryReference(dotGit)
            ?.let(::canonicalExistingPath)
            ?: return false
        if (declaredGitDirectory != actualGitDirectory) return false

        val worktreeRegistry = runCatching {
            commonGitDirectory.resolve("worktrees").toRealPath().normalize()
        }.getOrNull()
        val registeredWorktree = worktreeRegistry != null &&
            actualGitDirectory.parent == worktreeRegistry &&
            readPathReference(actualGitDirectory.resolve("gitdir")) == dotGit.toAbsolutePath().normalize()
        if (registeredWorktree) return true

        val configuredWorktree = gitOutput(root, "config", "--path", "--get", "core.worktree")
            ?.let { raw ->
                val rawPath = runCatching { Path.of(raw) }.getOrNull() ?: return@let null
                val resolved = if (rawPath.isAbsolute) rawPath else actualGitDirectory.resolve(rawPath)
                runCatching { resolved.toRealPath().normalize() }.getOrNull()
            }
        return actualGitDirectory == commonGitDirectory && configuredWorktree == root
    }

    private fun canonicalExistingPath(raw: String): Path? = runCatching {
        Path.of(raw).toRealPath().normalize()
    }.getOrNull()

    private fun readGitDirectoryReference(dotGit: Path): String? {
        val value = readSmallTextFile(dotGit) ?: return null
        val rawPath = value.removePrefix(GIT_DIRECTORY_PREFIX)
        if (rawPath == value || rawPath.isBlank()) return null
        val path = runCatching { Path.of(rawPath) }.getOrNull() ?: return null
        return (if (path.isAbsolute) path else dotGit.parent.resolve(path)).normalize().toString()
    }

    private fun readPathReference(path: Path): Path? {
        val value = readSmallTextFile(path) ?: return null
        val rawPath = runCatching { Path.of(value) }.getOrNull() ?: return null
        return (if (rawPath.isAbsolute) rawPath else path.parent.resolve(rawPath))
            .toAbsolutePath()
            .normalize()
    }

    private fun readSmallTextFile(path: Path): String? = try {
        val before = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!before.isRegularFile || before.size() > MAXIMUM_GIT_AUTHORITY_FILE_BYTES) return null
        val canonicalBefore = path.toRealPath().normalize()
        authorityReadCheckpoint.beforeControlFileRead(path)
        val content = FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.allocate(MAXIMUM_GIT_AUTHORITY_FILE_BYTES.toInt() + 1)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            if (!buffer.hasRemaining()) return null
            buffer.flip()
            StandardCharsets.UTF_8.decode(buffer).toString().trim()
        }
        val after = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val canonicalAfter = path.toRealPath().normalize()
        if (
            !after.isRegularFile ||
            canonicalAfter != canonicalBefore ||
            after.fileKey() != before.fileKey() ||
            after.size() != before.size() ||
            after.lastModifiedTime() != before.lastModifiedTime()
        ) {
            null
        } else {
            content
        }
    } catch (_: Exception) {
        null
    }

    private fun parseSourceState(
        input: RawSourceStateInput,
        root: CanonicalRepositoryRoot,
        compilationUnits: List<AdmittedCompilationUnit>,
        resourceBounds: EstablishedResourceBounds,
    ): ExactSourceState {
        val revision = input.revision
            ?.takeIf { raw -> raw.matches(EXACT_REVISION) }
            ?.lowercase()
            ?: return fail(RepositoryOperationRejection.SourceRevisionUnresolvable(input.revision))
        val resolvedRevision = gitOutput(
            Path.of(root.value),
            "rev-parse",
            "--verify",
            "--end-of-options",
            "$revision^{commit}",
        )?.lowercase()
        if (resolvedRevision != revision) {
            return fail(RepositoryOperationRejection.SourceRevisionUnresolvable(input.revision))
        }
        val rawInputs = input.inputs
            ?: return fail(
                RepositoryOperationRejection.SourceStateEvidenceMissing(
                    evidence = SourceStateEvidenceKind.INVENTORY,
                    path = null,
                ),
            )
        val admittedByPath = linkedMapOf<String, ExactSourceInput>()
        rawInputs.forEach { rawInput ->
            val rawPath = rawInput.path?.takeIf(String::isNotBlank)
                ?: return fail(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.PATH,
                        rawInput.path,
                    ),
                )
            val kind = rawInput.kind
                ?: return fail(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.KIND,
                        rawPath,
                    ),
                )
            val presence = rawInput.presence
                ?: return fail(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.PRESENCE,
                        rawPath,
                    ),
                )
            val disposition = rawInput.disposition
                ?: return fail(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.DISPOSITION,
                        rawPath,
                    ),
                )
            val path = parseRepositoryPath(root, rawPath)
            val digest = when {
                presence == RawSourceInputPresence.DELETED -> {
                    if (kind != RawSourceInputKind.TRACKED_CHANGE || rawInput.contentSha256 != null) {
                        return fail(RepositoryOperationRejection.SourceStateConflict(rawPath))
                    }
                    null
                }

                disposition == RawSourceInputDisposition.INCLUDED -> rawInput.contentSha256
                    ?.takeIf { raw -> raw.matches(SHA_256) }
                    ?.lowercase()
                    ?.let(SourceContentDigest::fromValidated)
                    ?: return fail(
                        RepositoryOperationRejection.SourceStateEvidenceMissing(
                            SourceStateEvidenceKind.CONTENT_DIGEST,
                            rawPath,
                        ),
                    )

                else -> {
                    if (rawInput.contentSha256 != null) {
                        return fail(RepositoryOperationRejection.SourceStateConflict(rawPath))
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
                return fail(RepositoryOperationRejection.SourceStateConflict(rawPath))
            }
            admittedByPath[path.value] = admitted
        }
        val admittedInputs = admittedByPath.values.sortedBy { sourceInput -> sourceInput.path.value }
        validateSourceStateAgainstRepository(
            root,
            revision,
            admittedInputs,
            compilationUnits,
            resourceBounds,
        )
        return ExactSourceState.create(
            revision = SourceRevision.fromValidated(revision),
            inputs = admittedInputs,
        )
    }

    private fun validateSourceStateAgainstRepository(
        root: CanonicalRepositoryRoot,
        revision: String,
        inputs: List<ExactSourceInput>,
        compilationUnits: List<AdmittedCompilationUnit>,
        resourceBounds: EstablishedResourceBounds,
    ) {
        val deadlineNanos = deadlineAfter(resourceBounds.timeLimitMillis.value)
        validateSourceStateObservation(
            root = root,
            revision = revision,
            inputs = inputs,
            compilationUnits = compilationUnits,
            resourceBounds = resourceBounds,
            deadlineNanos = deadlineNanos,
        )
        stabilityCheckpoint.afterInitialValidation()
        validateSourceStateObservation(
            root = root,
            revision = revision,
            inputs = inputs,
            compilationUnits = compilationUnits,
            resourceBounds = resourceBounds,
            deadlineNanos = deadlineNanos,
        )
    }

    private fun validateSourceStateObservation(
        root: CanonicalRepositoryRoot,
        revision: String,
        inputs: List<ExactSourceInput>,
        compilationUnits: List<AdmittedCompilationUnit>,
        resourceBounds: EstablishedResourceBounds,
        deadlineNanos: Long,
    ) {
        val rootPath = Path.of(root.value)
        if (remainingMillis(deadlineNanos) == null) {
            return fail(RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME))
        }
        val headRevision = gitOutput(rootPath, "rev-parse", "HEAD")?.lowercase()
        if (remainingMillis(deadlineNanos) == null) {
            return fail(RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME))
        }
        if (headRevision != revision) {
            return fail(RepositoryOperationRejection.SourceRevisionUnresolvable(revision))
        }
        val inventoryBudget = SourceInventoryBudget(
            memoryLimitBytes = resourceBounds.memoryLimitBytes.value,
            pathLimit = resourceBounds.pathLimit.value,
        )
        val indexRecords = requiredGitInventoryPaths(
            rootPath,
            inventoryBudget,
            deadlineNanos,
            "ls-files",
            "-v",
            "-z",
            "--",
        )
        indexRecords.forEach { record ->
            if (record.length < GIT_INDEX_RECORD_PREFIX_LENGTH || record[1] != ' ') {
                return fail(
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.INVENTORY,
                        null,
                    ),
                )
            }
            val tag = record.first()
            if (tag == GIT_SKIP_WORKTREE_TAG || tag.isLowerCase()) {
                val rawPath = record.substring(GIT_INDEX_RECORD_PREFIX_LENGTH)
                val path = parseRepositoryPath(root, rawPath)
                return fail(RepositoryOperationRejection.SourceStateConflict(path.value))
            }
        }
        val trackedPaths = requiredGitInventoryPaths(
            rootPath,
            inventoryBudget,
            deadlineNanos,
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
            val path = parseRepositoryPath(root, rawPath)
            actualByPath[path.value] = LiveSourceKind.TRACKED_CHANGE
        }
        untrackedPaths.forEach { rawPath ->
            val path = parseRepositoryPath(root, rawPath)
            val kind = if (generatedSourceRoots.any { generatedRoot -> path.isWithin(generatedRoot) }) {
                LiveSourceKind.GENERATED
            } else {
                LiveSourceKind.UNTRACKED
            }
            actualByPath.putIfAbsent(path.value, kind)
        }
        ignoredGeneratedPaths.forEach { rawPath ->
            val path = parseRepositoryPath(root, rawPath)
            val kind = if (generatedSourceRoots.any { generatedRoot -> path.isWithin(generatedRoot) }) {
                LiveSourceKind.GENERATED
            } else {
                LiveSourceKind.UNTRACKED
            }
            actualByPath.putIfAbsent(path.value, kind)
        }
        if (actualByPath.size > resourceBounds.pathLimit.value) {
            return fail(RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.PATHS))
        }
        val admittedByPath = inputs.associateBy { input -> input.path.value }
        actualByPath.forEach { (path, liveKind) ->
            val evidence = admittedByPath[path]
                ?: return fail(RepositoryOperationRejection.SourceStateConflict(path))
            val livePresence = if (Files.exists(rootPath.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                RawSourceInputPresence.PRESENT
            } else {
                RawSourceInputPresence.DELETED
            }
            if (evidence.presence != livePresence || !liveKind.accepts(evidence.kind)) {
                return fail(RepositoryOperationRejection.SourceStateConflict(path))
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
                return fail(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
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
                        )
                    ) {
                        is ContentDigestResult.Success -> digest.value
                        ContentDigestResult.TimeExceeded -> return fail(
                            RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME),
                        )
                        ContentDigestResult.Unavailable -> return fail(
                            RepositoryOperationRejection.SourceStateConflict(evidence.path.value),
                        )
                    }
                    if (liveDigest != evidence.contentDigest.value) {
                        return fail(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
                    }
                }

                is ExactSourceInput.ExcludedFile -> if (!Files.exists(
                    sourcePath,
                    LinkOption.NOFOLLOW_LINKS,
                )) {
                    return fail(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
                }

                is ExactSourceInput.DeletedTrackedInput -> if (Files.exists(
                    sourcePath,
                    LinkOption.NOFOLLOW_LINKS,
                )) {
                    return fail(RepositoryOperationRejection.SourceStateConflict(evidence.path.value))
                }
            }
        }
    }

    private fun parseCompilationUnits(
        evidence: RawBuildOwnershipEvidence,
        root: CanonicalRepositoryRoot,
    ): List<AdmittedCompilationUnit> {
        val rawUnits = when (evidence) {
            RawBuildOwnershipEvidence.Unavailable ->
                return fail(RepositoryOperationRejection.BuildOwnershipEvidenceUnavailable)

            is RawBuildOwnershipEvidence.Available -> evidence.compilationUnits
                ?: return fail(
                    RepositoryOperationRejection.SemanticConfigurationIncomplete(
                        SemanticConfigurationField.COMPILATION_UNITS,
                        null,
                    ),
                )
        }
        if (rawUnits.isEmpty()) {
            return fail(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(
                    SemanticConfigurationField.COMPILATION_UNITS,
                    null,
                ),
            )
        }
        val units = rawUnits.map { rawUnit -> parseCompilationUnit(rawUnit, root) }
        val duplicateId = units.groupBy { unit -> unit.id.value }
            .entries
            .firstOrNull { (_, matches) -> matches.size > 1 }
            ?.key
        if (duplicateId != null) {
            return fail(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(
                    SemanticConfigurationField.OWNER_ID,
                    duplicateId,
                ),
            )
        }
        val conflictingModuleIdentity = units.groupBy { unit -> unit.moduleIdentity }
            .entries
            .firstOrNull { (_, matches) -> matches.map { unit -> unit.moduleName }.distinct().size > 1 }
            ?.key
        if (conflictingModuleIdentity != null) {
            return fail(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(
                    SemanticConfigurationField.MODULE_IDENTITY,
                    conflictingModuleIdentity.value,
                ),
            )
        }
        val unitIds = units.map { unit -> unit.id }.toSet()
        units.forEach { unit ->
            val invalidRelationship = unit.sourceSetRelationships.firstOrNull { relationship ->
                relationship.targetCompilationUnitId == unit.id ||
                    relationship.targetCompilationUnitId !in unitIds
            }
            if (invalidRelationship != null) {
                return fail(
                    RepositoryOperationRejection.SemanticConfigurationIncomplete(
                        SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                        unit.id.value,
                    ),
                )
            }
        }
        val unitsById = units.associateBy { unit -> unit.id }
        val visited = mutableSetOf<CompilationUnitId>()
        val active = linkedSetOf<CompilationUnitId>()
        fun findCycle(unit: AdmittedCompilationUnit): CompilationUnitId? {
            if (!active.add(unit.id)) return unit.id
            if (unit.id in visited) {
                active.remove(unit.id)
                return null
            }
            unit.sourceSetRelationships.forEach { relationship ->
                val target = requireNotNull(unitsById[relationship.targetCompilationUnitId])
                val cycle = findCycle(target)
                if (cycle != null) return cycle
            }
            active.remove(unit.id)
            visited.add(unit.id)
            return null
        }
        units.forEach { unit ->
            val cycle = findCycle(unit)
            if (cycle != null) {
                return fail(
                    RepositoryOperationRejection.SemanticConfigurationIncomplete(
                        SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                        cycle.value,
                    ),
                )
            }
        }
        return units.sortedBy { unit -> unit.id.value }
    }

    private fun parseCompilationUnit(
        input: RawCompilationUnitInput,
        root: CanonicalRepositoryRoot,
    ): AdmittedCompilationUnit {
        val ownerId = requiredBuildValue(input.ownerId, SemanticConfigurationField.OWNER_ID, input.ownerId)
        val moduleIdentity = requiredBuildValue(
            input.moduleIdentity,
            SemanticConfigurationField.MODULE_IDENTITY,
            ownerId,
        )
        val moduleName = requiredBuildValue(input.moduleName, SemanticConfigurationField.MODULE, ownerId)
        val sourceSetName = requiredBuildValue(input.sourceSetName, SemanticConfigurationField.SOURCE_SET, ownerId)
        val variantName = requiredBuildValue(input.variantName, SemanticConfigurationField.VARIANT, ownerId)
        val rawSourceRoots = input.sourceRoots
            ?: return fail(incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId))
        if (rawSourceRoots.isEmpty()) {
            return fail(incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId))
        }
        val sourceRoots = rawSourceRoots.map { rawPath ->
            if (rawPath.isBlank()) {
                return fail(incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId))
            }
            parseRepositoryPath(root, rawPath)
        }.distinct().sortedBy(RepositoryRelativePath::value)
        val rawGeneratedSourceRoots = input.generatedSourceRoots
            ?: return fail(incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId))
        val generatedSourceRoots = rawGeneratedSourceRoots.map { rawPath ->
            if (rawPath.isBlank()) {
                return fail(incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId))
            }
            val generatedRoot = parseRepositoryPath(root, rawPath)
            if (sourceRoots.none { sourceRoot -> generatedRoot.isWithin(sourceRoot) }) {
                return fail(incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId))
            }
            generatedRoot
        }.distinct().sortedBy(RepositoryRelativePath::value)
        val rawDeclarations = input.declarations
            ?: return fail(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
        val declarations = rawDeclarations.map { declaration ->
            val name = declaration.fullyQualifiedName?.takeIf(String::isNotBlank)
                ?: return fail(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
            val rawPath = declaration.path?.takeIf(String::isNotBlank)
                ?: return fail(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
            val path = parseRepositoryPath(root, rawPath)
            if (sourceRoots.none { sourceRoot -> path.isWithin(sourceRoot) }) {
                return fail(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
            }
            AdmittedDeclaration.create(
                name = AdmittedDeclarationName.fromValidated(name),
                path = path,
            )
        }.sortedWith(compareBy({ declaration -> declaration.name.value }, { declaration -> declaration.path.value }))
        val rawFamilies = input.families
            ?: return fail(incomplete(SemanticConfigurationField.FAMILIES, ownerId))
        if (rawFamilies.any(String::isBlank)) {
            return fail(incomplete(SemanticConfigurationField.FAMILIES, ownerId))
        }
        val rawRelationships = input.sourceSetRelationships
            ?: return fail(incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, ownerId))
        val relationships = rawRelationships.map { relationship ->
            val kind = relationship.kind
                ?: return fail(incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, ownerId))
            val target = requiredBuildValue(
                relationship.targetCompilationUnitId,
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                ownerId,
            )
            SourceSetRelationship.create(
                kind = kind,
                targetCompilationUnitId = CompilationUnitId.fromValidated(target),
            )
        }.toSet()
        val compiler = input.compiler
            ?: return fail(incomplete(SemanticConfigurationField.COMPILER, ownerId))
        val configuration = parseSemanticConfiguration(
            input = compiler,
            variantName = variantName,
            ownerId = ownerId,
        )
        return AdmittedCompilationUnit.create(
            id = CompilationUnitId.fromValidated(ownerId),
            moduleIdentity = AdmittedModuleIdentity.fromValidated(moduleIdentity),
            moduleName = AdmittedModuleName.fromValidated(moduleName),
            sourceSetName = AdmittedSourceSetName.fromValidated(sourceSetName),
            variantName = AdmittedVariantName.fromValidated(variantName),
            sourceRoots = sourceRoots,
            generatedSourceRoots = generatedSourceRoots,
            declarations = declarations,
            families = rawFamilies.map(SemanticFamilyName::fromValidated).toSet(),
            sourceSetRelationships = relationships,
            semanticConfiguration = configuration,
        )
    }

    private fun parseSemanticConfiguration(
        input: RawCompilerInput,
        variantName: String,
        ownerId: String,
    ): CoherentSemanticConfiguration {
        val compilerVersion = requiredBuildValue(
            input.compilerVersion,
            SemanticConfigurationField.COMPILER_VERSION,
            ownerId,
        )
        val languageVersion = requiredBuildValue(
            input.languageVersion,
            SemanticConfigurationField.LANGUAGE_VERSION,
            ownerId,
        )
        val apiVersion = requiredBuildValue(
            input.apiVersion,
            SemanticConfigurationField.API_VERSION,
            ownerId,
        )
        val rawSettings = input.languageSettings
            ?: return fail(incomplete(SemanticConfigurationField.LANGUAGE_SETTINGS, ownerId))
        if (rawSettings.any { (key, value) -> key.isBlank() || value.isBlank() }) {
            return fail(incomplete(SemanticConfigurationField.LANGUAGE_SETTINGS, ownerId))
        }
        val compilerImplementation = parseResolvedArtifact(
            input.compilerImplementation,
            SemanticConfigurationField.COMPILER_IMPLEMENTATION,
            ownerId,
        )
        val toolchain = parseCompilerToolchain(input.toolchain, ownerId)
        val rawOptions = input.compilerOptions
            ?: return fail(incomplete(SemanticConfigurationField.COMPILER_OPTIONS, ownerId))
        val compilerOptions = rawOptions.map { option ->
            val token = requiredBuildValue(
                option.token,
                SemanticConfigurationField.COMPILER_OPTIONS,
                ownerId,
            )
            CompilerOptionToken.fromValidated(token)
        }
        val rawDependencies = input.resolvedDependencies
            ?: return fail(incomplete(SemanticConfigurationField.DEPENDENCIES, ownerId))
        val dependencies = rawDependencies.map { dependency ->
            parseResolvedArtifact(dependency, SemanticConfigurationField.DEPENDENCIES, ownerId)
        }
        val rawPlugins = input.compilerPlugins
            ?: return fail(incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId))
        val plugins = rawPlugins.map { plugin -> parseCompilerPlugin(plugin, ownerId) }
        val settings = rawSettings.entries
            .sortedBy { (key, _) -> key }
            .associate { (key, value) ->
                LanguageSettingName.fromValidated(key) to LanguageSettingValue.fromValidated(value)
            }
        return CoherentSemanticConfiguration.create(
            compilerVersion = CompilerVersion.fromValidated(compilerVersion),
            languageVersion = LanguageVersion.fromValidated(languageVersion),
            apiVersion = ApiVersion.fromValidated(apiVersion),
            variantName = AdmittedVariantName.fromValidated(variantName),
            languageSettings = settings,
            compilerImplementation = compilerImplementation,
            toolchain = toolchain,
            compilerOptions = compilerOptions,
            resolvedDependencies = dependencies,
            compilerPlugins = plugins,
        )
    }

    private fun parseResolvedArtifact(
        input: RawResolvedArtifactInput?,
        field: SemanticConfigurationField,
        ownerId: String,
    ): ResolvedBuildArtifact {
        val artifact = input ?: return fail(incomplete(field, ownerId))
        val componentIdentity = requiredBuildValue(artifact.componentIdentity, field, ownerId)
        val selectedVariantIdentity = requiredBuildValue(artifact.selectedVariantIdentity, field, ownerId)
        val contentKind = artifact.contentKind ?: return fail(incomplete(field, ownerId))
        val contentDigest = artifact.contentSha256
            ?.takeIf { digest -> digest.matches(SHA_256) }
            ?.lowercase()
            ?: return fail(incomplete(field, ownerId))
        return ResolvedBuildArtifact.create(
            componentIdentity = BuildComponentIdentity.fromValidated(componentIdentity),
            selectedVariantIdentity = SelectedBuildVariantIdentity.fromValidated(selectedVariantIdentity),
            contentKind = contentKind,
            contentDigest = ArtifactContentDigest.fromValidated(contentDigest),
        )
    }

    private fun parseCompilerToolchain(
        input: RawCompilerToolchainInput?,
        ownerId: String,
    ): CompilerToolchain {
        val toolchain = input
            ?: return fail(incomplete(SemanticConfigurationField.TOOLCHAIN, ownerId))
        val digest = toolchain.contentSha256
            ?.takeIf { value -> value.matches(SHA_256) }
            ?.lowercase()
            ?: return fail(incomplete(SemanticConfigurationField.TOOLCHAIN, ownerId))
        return CompilerToolchain.create(
            targetPlatform = CompilerTargetPlatform.fromValidated(
                requiredBuildValue(toolchain.targetPlatform, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            version = CompilerToolchainVersion.fromValidated(
                requiredBuildValue(toolchain.version, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            vendor = CompilerToolchainVendor.fromValidated(
                requiredBuildValue(toolchain.vendor, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            implementation = CompilerToolchainImplementation.fromValidated(
                requiredBuildValue(toolchain.implementation, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            contentDigest = ArtifactContentDigest.fromValidated(digest),
        )
    }

    private fun parseCompilerPlugin(
        input: RawCompilerPluginInput,
        ownerId: String,
    ): CompilerPluginInvocation {
        val field = SemanticConfigurationField.COMPILER_PLUGINS
        val pluginId = requiredBuildValue(input.pluginId, field, ownerId)
        val rawClasspath = input.classpath ?: return fail(incomplete(field, ownerId))
        if (rawClasspath.isEmpty()) {
            return fail(incomplete(field, ownerId))
        }
        val classpath = rawClasspath.map { artifact -> parseResolvedArtifact(artifact, field, ownerId) }
        val rawOptions = input.options ?: return fail(incomplete(field, ownerId))
        val options = rawOptions.map { option ->
            CompilerOptionToken.fromValidated(requiredBuildValue(option.token, field, ownerId))
        }
        return CompilerPluginInvocation.create(
            pluginId = CompilerPluginId.fromValidated(pluginId),
            classpath = classpath,
            options = options,
        )
    }

    private fun parseScope(
        selectors: List<RawScopeSelector>,
        units: List<AdmittedCompilationUnit>,
    ): ParsedScope {
        if (selectors.isEmpty()) {
            return fail(RepositoryOperationRejection.ScopeResolvesToNothing)
        }
        val selected = linkedMapOf<String, AdmittedCompilationUnit>()
        selectors.forEach { selector ->
            val matches = when (selector) {
                is RawScopeSelector.Module -> when (
                    val module = resolveModuleReference(selector.moduleName, units)
                ) {
                    ModuleReferenceResolution.Unknown -> emptyList()
                    is ModuleReferenceResolution.Ambiguous -> return fail(
                        RepositoryOperationRejection.AmbiguousScope(selector, module.moduleIdentities),
                    )
                    is ModuleReferenceResolution.Resolved -> module.units
                }

                is RawScopeSelector.SourceSet -> {
                    val sourceSetName = selector.sourceSetName?.takeIf(String::isNotBlank)
                    if (sourceSetName == null) {
                        emptyList()
                    } else {
                        when (val module = resolveModuleReference(selector.moduleName, units)) {
                            ModuleReferenceResolution.Unknown -> emptyList()
                            is ModuleReferenceResolution.Ambiguous -> return fail(
                                RepositoryOperationRejection.AmbiguousScope(selector, module.moduleIdentities),
                            )
                            is ModuleReferenceResolution.Resolved -> module.units.filter { unit ->
                                unit.sourceSetName.value == sourceSetName
                            }
                        }
                    }
                }

                is RawScopeSelector.Declaration -> selector.fullyQualifiedName
                    ?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        val occurrences = units.flatMap { unit ->
                            unit.declarations
                                .filter { declaration -> declaration.name.value == name }
                                .map { declaration -> unit to declaration }
                        }
                        if (occurrences.size > 1) {
                            return fail(
                                RepositoryOperationRejection.AmbiguousScope(
                                    selector = selector,
                                    candidates = occurrences.map { (unit, declaration) ->
                                        "${unit.id.value}:${declaration.path.value}"
                                    }.sorted(),
                                ),
                            )
                        }
                        occurrences.map { (unit, _) -> unit }
                    }
                    .orEmpty()

                is RawScopeSelector.Family -> selector.familyName
                    ?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        units.filter { unit -> unit.families.any { family -> family.value == name } }
                    }
                    .orEmpty()
            }
            if (matches.isEmpty()) {
                return fail(RepositoryOperationRejection.UnknownScope(selector))
            }
            val singularSelector = selector is RawScopeSelector.SourceSet ||
                selector is RawScopeSelector.Declaration ||
                selector is RawScopeSelector.Family
            if (singularSelector && matches.size > 1) {
                return fail(
                    RepositoryOperationRejection.AmbiguousScope(
                        selector = selector,
                        candidates = matches.map { unit -> unit.id.value }.sorted(),
                    ),
                )
            }
            relationshipClosure(matches, units).forEach { unit -> selected[unit.id.value] = unit }
        }
        if (selected.isEmpty()) {
            return fail(RepositoryOperationRejection.ScopeResolvesToNothing)
        }
        val selectedUnits = selected.values.sortedBy { unit -> unit.id.value }
        val configurations = selectedUnits.map { unit -> unit.semanticConfiguration.identity }.distinct()
        if (configurations.size != 1) {
            return fail(
                RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                    selectedUnits.map { unit -> unit.id.value },
                ),
            )
        }
        return ParsedScope(
            scope = ResolvedRepositoryScope.create(selectedUnits),
            semanticConfiguration = selectedUnits.first().semanticConfiguration,
        )
    }

    private fun resolveModuleReference(
        rawReference: String?,
        units: List<AdmittedCompilationUnit>,
    ): ModuleReferenceResolution {
        val reference = rawReference?.takeIf(String::isNotBlank)
            ?: return ModuleReferenceResolution.Unknown
        val exactMatches = units.filter { unit -> unit.moduleIdentity.value == reference }
        val displayNameMatches = units.filter { unit -> unit.moduleName.value == reference }
        val matches = (exactMatches + displayNameMatches).distinctBy { unit -> unit.id }
        if (matches.isEmpty()) {
            return ModuleReferenceResolution.Unknown
        }
        val moduleIdentities = matches
            .map { unit -> unit.moduleIdentity.value }
            .distinct()
            .sorted()
        return if (moduleIdentities.size == 1) {
            ModuleReferenceResolution.Resolved(matches)
        } else {
            ModuleReferenceResolution.Ambiguous(moduleIdentities)
        }
    }

    private fun relationshipClosure(
        roots: List<AdmittedCompilationUnit>,
        units: List<AdmittedCompilationUnit>,
    ): List<AdmittedCompilationUnit> {
        val unitsById = units.associateBy { unit -> unit.id }
        val closure = linkedMapOf<CompilationUnitId, AdmittedCompilationUnit>()
        val pending = ArrayDeque<AdmittedCompilationUnit>()
        pending.addAll(roots)
        while (pending.isNotEmpty()) {
            val unit = pending.removeFirst()
            if (closure.putIfAbsent(unit.id, unit) != null) continue
            unit.sourceSetRelationships.forEach { relationship ->
                pending.addLast(requireNotNull(unitsById[relationship.targetCompilationUnitId]))
            }
        }
        return closure.values.sortedBy { unit -> unit.id.value }
    }

    private fun parseResourceBounds(input: RawResourceBoundsInput): EstablishedResourceBounds {
        val rawBounds = listOf(
            ResourceBoundKind.TIME to input.timeLimitMillis,
            ResourceBoundKind.MEMORY to input.memoryLimitBytes,
            ResourceBoundKind.DEPTH to input.traversalDepthLimit?.toLong(),
            ResourceBoundKind.PATHS to input.pathLimit?.toLong(),
            ResourceBoundKind.RESULTS to input.resultLimit?.toLong(),
        )
        val missing = rawBounds.firstOrNull { (_, value) -> value == null }
        if (missing != null) {
            return fail(RepositoryOperationRejection.ResourceBoundMissing(missing.first))
        }
        val invalid = rawBounds.firstOrNull { (_, value) -> requireNotNull(value) <= 0L }
        if (invalid != null) {
            return fail(
                RepositoryOperationRejection.ResourceBoundInvalid(
                    bound = invalid.first,
                    rawValue = requireNotNull(invalid.second),
                ),
            )
        }
        return EstablishedResourceBounds.create(
            timeLimitMillis = AnalysisTimeLimitMillis.fromValidated(requireNotNull(input.timeLimitMillis)),
            memoryLimitBytes = AnalysisMemoryLimitBytes.fromValidated(requireNotNull(input.memoryLimitBytes)),
            traversalDepthLimit = TraversalDepthLimit.fromValidated(requireNotNull(input.traversalDepthLimit)),
            pathLimit = AnalysisPathLimit.fromValidated(requireNotNull(input.pathLimit)),
            resultLimit = AnalysisResultLimit.fromValidated(requireNotNull(input.resultLimit)),
        )
    }

    private fun parseRepositoryPath(
        root: CanonicalRepositoryRoot,
        raw: String,
    ): RepositoryRelativePath {
        val rootPath = Path.of(root.value)
        val rawPath = runCatching { Path.of(raw) }.getOrNull()
            ?: return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
        val candidate = (if (rawPath.isAbsolute) rawPath else rootPath.resolve(rawPath))
            .toAbsolutePath()
            .normalize()
        if (!candidate.startsWith(rootPath)) {
            return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
        }
        val lexicalRelativePath = rootPath.relativize(candidate).normalize()
        var current = rootPath
        for (segment in lexicalRelativePath) {
            current = current.resolve(segment)
            val attributes = try {
                Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                break
            } catch (_: Exception) {
                return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            }
            if (attributes.isSymbolicLink) {
                return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            }
            val canonicalCurrent = runCatching { current.toRealPath().normalize() }.getOrNull()
                ?: return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            if (!canonicalCurrent.startsWith(rootPath)) {
                return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            }
        }
        val relative = lexicalRelativePath.portablePath()
        if (relative.isBlank()) {
            return fail(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
        }
        return RepositoryRelativePath.fromValidated(relative)
    }

    private fun requiredBuildValue(
        raw: String?,
        field: SemanticConfigurationField,
        ownerId: String?,
    ): String = raw?.takeIf(String::isNotBlank) ?: fail(incomplete(field, ownerId))

    private fun incomplete(
        field: SemanticConfigurationField,
        ownerId: String?,
    ): RepositoryOperationRejection.SemanticConfigurationIncomplete =
        RepositoryOperationRejection.SemanticConfigurationIncomplete(field, ownerId)

    private fun ExactSourceInput.sameEvidenceAs(other: ExactSourceInput): Boolean =
        path == other.path &&
            kind == other.kind &&
            presence == other.presence &&
            disposition == other.disposition &&
            contentDigest == other.contentDigest

    private fun RepositoryRelativePath.isWithin(root: RepositoryRelativePath): Boolean =
        Path.of(value).startsWith(Path.of(root.value))

    private fun Path.portablePath(): String = joinToString("/") { segment -> segment.toString() }

    private fun gitOutput(
        workingDirectory: Path,
        vararg arguments: String,
    ): String? {
        var process: Process? = null
        return try {
            process = gitProcessBuilder(workingDirectory, *arguments)
                .directory(workingDirectory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
                    .takeIf { output -> process.exitValue() == 0 && output.isNotBlank() }
            }
        } catch (_: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            process?.destroyForcibly()
            null
        }
    }

    private fun requiredGitInventoryPaths(
        workingDirectory: Path,
        budget: SourceInventoryBudget,
        deadlineNanos: Long,
        vararg arguments: String,
    ): List<String> = when (
        val result = gitInventoryPaths(
            workingDirectory,
            budget,
            deadlineNanos,
            *arguments,
        )
    ) {
        is GitInventoryResult.Success -> result.paths
        is GitInventoryResult.BoundExceeded -> fail(
            RepositoryOperationRejection.ResourceBoundExceeded(result.bound),
        )
        GitInventoryResult.Unavailable -> fail(
            RepositoryOperationRejection.SourceStateEvidenceMissing(
                SourceStateEvidenceKind.INVENTORY,
                null,
            ),
        )
    }

    private fun gitInventoryPaths(
        workingDirectory: Path,
        budget: SourceInventoryBudget,
        deadlineNanos: Long,
        vararg arguments: String,
    ): GitInventoryResult {
        val output = runCatching {
            Files.createTempFile("kast-repository-admission-", ".git-output")
        }.getOrNull() ?: return GitInventoryResult.Unavailable
        var process: Process? = null
        return try {
            process = runCatching {
                gitProcessBuilder(workingDirectory, *arguments)
                    .directory(workingDirectory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(output.toFile())
                    .start()
            }.getOrNull() ?: return GitInventoryResult.Unavailable
            val timeoutMillis = remainingMillis(deadlineNanos)?.coerceAtMost(GIT_TIMEOUT_MILLIS)
                ?: return GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return if (remainingMillis(deadlineNanos) == null) {
                    GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
                } else {
                    GitInventoryResult.Unavailable
                }
            }
            if (process.exitValue() != 0) {
                return GitInventoryResult.Unavailable
            }
            if (remainingMillis(deadlineNanos) == null) {
                return GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
            }
            val outputSize = Files.size(output)
            if (outputSize > budget.remainingMemoryBytes) {
                return GitInventoryResult.BoundExceeded(ResourceBoundKind.MEMORY)
            }
            val paths = mutableListOf<String>()
            if (outputSize > 0) {
                val record = ByteArrayOutputStream(
                    minOf(outputSize, MAXIMUM_GIT_PATH_BYTES).toInt(),
                )
                Files.newInputStream(output).buffered().use { stream ->
                    while (true) {
                        if (remainingMillis(deadlineNanos) == null) {
                            return GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
                        }
                        when (val next = stream.read()) {
                            -1 -> {
                                if (record.size() != 0) return GitInventoryResult.Unavailable
                                break
                            }

                            0 -> {
                                if (record.size() == 0) return GitInventoryResult.Unavailable
                                if (paths.size >= budget.remainingPathCount) {
                                    return GitInventoryResult.BoundExceeded(ResourceBoundKind.PATHS)
                                }
                                paths += record.toString(StandardCharsets.UTF_8)
                                record.reset()
                            }

                            else -> {
                                if (record.size().toLong() >= MAXIMUM_GIT_PATH_BYTES) {
                                    return GitInventoryResult.Unavailable
                                }
                                record.write(next)
                            }
                        }
                    }
                }
            }
            when (val exceeded = budget.consume(outputSize, paths.size)) {
                null -> GitInventoryResult.Success(paths.toList())
                else -> GitInventoryResult.BoundExceeded(exceeded)
            }
        } catch (_: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            GitInventoryResult.Unavailable
        } catch (_: Exception) {
            process?.destroyForcibly()
            GitInventoryResult.Unavailable
        } finally {
            runCatching { Files.deleteIfExists(output) }
        }
    }

    private fun gitProcessBuilder(
        workingDirectory: Path,
        vararg arguments: String,
    ): ProcessBuilder = ProcessBuilder("git", *arguments).also { builder ->
        builder.directory(workingDirectory.toFile())
        GIT_ENVIRONMENT_OVERRIDES.forEach { variable -> builder.environment().remove(variable) }
    }

    private fun sha256(
        root: Path,
        relativePath: RepositoryRelativePath,
        memoryLimitBytes: Long,
        deadlineNanos: Long,
    ): ContentDigestResult {
        if (remainingMillis(deadlineNanos) == null) return ContentDigestResult.TimeExceeded
        return try {
            val path = root.resolve(relativePath.value)
            val before = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!before.isRegularFile) return ContentDigestResult.Unavailable
            val canonicalBefore = path.toRealPath().normalize()
            if (!canonicalBefore.startsWith(root)) return ContentDigestResult.Unavailable
            val digestValue = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteBuffer.allocate(minOf(memoryLimitBytes, HASH_BUFFER_BYTES).toInt())
                while (true) {
                    if (remainingMillis(deadlineNanos) == null) return ContentDigestResult.TimeExceeded
                    val read = channel.read(buffer)
                    if (read < 0) break
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            if (remainingMillis(deadlineNanos) == null) return ContentDigestResult.TimeExceeded
            val after = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val canonicalAfter = path.toRealPath().normalize()
            if (
                !after.isRegularFile ||
                canonicalAfter != canonicalBefore ||
                after.fileKey() != before.fileKey() ||
                after.size() != before.size() ||
                after.lastModifiedTime() != before.lastModifiedTime()
            ) {
                return ContentDigestResult.Unavailable
            }
            if (remainingMillis(deadlineNanos) == null) {
                ContentDigestResult.TimeExceeded
            } else {
                ContentDigestResult.Success(digestValue)
            }
        } catch (_: Exception) {
            ContentDigestResult.Unavailable
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Long? {
        val remainingNanos = deadlineNanos - nanoTime()
        if (remainingNanos <= 0) return null
        return ((remainingNanos - 1) / NANOS_PER_MILLISECOND) + 1
    }

    private fun deadlineAfter(timeLimitMillis: Long): Long {
        val durationNanos = timeLimitMillis
            .coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLISECOND) * NANOS_PER_MILLISECOND
        val now = nanoTime()
        return if (now > Long.MAX_VALUE - durationNanos) Long.MAX_VALUE else now + durationNanos
    }

    private fun fail(rejection: RepositoryOperationRejection): Nothing =
        throw AdmissionParseRejected(rejection)

    private data class ParsedScope(
        val scope: ResolvedRepositoryScope,
        val semanticConfiguration: CoherentSemanticConfiguration,
    )

    private sealed interface ModuleReferenceResolution {
        data object Unknown : ModuleReferenceResolution

        data class Ambiguous(
            val moduleIdentities: List<String>,
        ) : ModuleReferenceResolution

        data class Resolved(
            val units: List<AdmittedCompilationUnit>,
        ) : ModuleReferenceResolution
    }

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

    private class SourceInventoryBudget(
        memoryLimitBytes: Long,
        pathLimit: Int,
    ) {
        var remainingMemoryBytes: Long = memoryLimitBytes
            private set

        var remainingPathCount: Int = pathLimit
            private set

        fun consume(byteCount: Long, pathCount: Int): ResourceBoundKind? = when {
            byteCount > remainingMemoryBytes -> ResourceBoundKind.MEMORY
            pathCount > remainingPathCount -> ResourceBoundKind.PATHS
            else -> {
                remainingMemoryBytes -= byteCount
                remainingPathCount -= pathCount
                null
            }
        }
    }

    private sealed interface GitInventoryResult {
        data class Success(
            val paths: List<String>,
        ) : GitInventoryResult

        data class BoundExceeded(
            val bound: ResourceBoundKind,
        ) : GitInventoryResult

        data object Unavailable : GitInventoryResult
    }

    private sealed interface ContentDigestResult {
        data class Success(
            val value: String,
        ) : ContentDigestResult

        data object TimeExceeded : ContentDigestResult

        data object Unavailable : ContentDigestResult
    }

    private class AdmissionParseRejected(
        val rejection: RepositoryOperationRejection,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        val EXACT_REVISION: Regex = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
        val SHA_256: Regex = Regex("[0-9a-fA-F]{64}")
        const val GIT_TIMEOUT_SECONDS: Long = 5
        const val GIT_TIMEOUT_MILLIS: Long = GIT_TIMEOUT_SECONDS * 1_000
        const val MAXIMUM_GIT_PATH_BYTES: Long = 4_096
        const val HASH_BUFFER_BYTES: Long = 8_192
        const val MAXIMUM_GIT_AUTHORITY_FILE_BYTES: Long = 4_096
        const val NANOS_PER_MILLISECOND: Long = 1_000_000
        const val GIT_INDEX_RECORD_PREFIX_LENGTH: Int = 2
        const val GIT_SKIP_WORKTREE_TAG: Char = 'S'
        const val GIT_DIRECTORY_PREFIX: String = "gitdir: "
        val GIT_ENVIRONMENT_OVERRIDES: Set<String> = setOf(
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_CEILING_DIRECTORIES",
            "GIT_COMMON_DIR",
            "GIT_DIR",
            "GIT_DISCOVERY_ACROSS_FILESYSTEM",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_WORK_TREE",
        )
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

internal fun interface GitAuthorityReadCheckpoint {
    fun beforeControlFileRead(path: Path)

    companion object {
        val NO_OP: GitAuthorityReadCheckpoint = GitAuthorityReadCheckpoint {}
    }
}
