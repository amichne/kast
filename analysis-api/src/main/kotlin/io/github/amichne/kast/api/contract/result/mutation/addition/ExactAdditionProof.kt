package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import java.nio.file.Path
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
private value class AdditionContextFilePath(val value: String) {
    init {
        requireCanonicalAbsolutePath(value, "Addition context file path")
    }
}

@Serializable
@JvmInline
private value class AdditionContextFileSha256(val value: String) {
    init {
        requireLowercaseSha256(value, "Addition context file SHA-256")
    }
}

@Serializable
class ExactAdditionContextFileHash private constructor(
    @DocField(description = "Normalized absolute path of one source context file.")
    @SerialName("filePath")
    private val storedFilePath: AdditionContextFilePath,
    @DocField(description = "SHA-256 of the exact source context file bytes.")
    @SerialName("sha256")
    private val storedSha256: AdditionContextFileSha256,
) {
    val filePath: String
        get() = storedFilePath.value

    val sha256: String
        get() = storedSha256.value

    override fun equals(other: Any?): Boolean = other is ExactAdditionContextFileHash &&
        storedFilePath == other.storedFilePath && storedSha256 == other.storedSha256

    override fun hashCode(): Int = 31 * storedFilePath.hashCode() + storedSha256.hashCode()

    companion object {
        fun of(filePath: String, sha256: String): ExactAdditionContextFileHash = ExactAdditionContextFileHash(
            storedFilePath = AdditionContextFilePath(filePath),
            storedSha256 = AdditionContextFileSha256(sha256),
        )
    }
}

@Serializable
class ExactAdditionProofContext private constructor(
    @DocField(description = "Semantic source generation required by this addition proof.")
    val requiredGeneration: MutationSemanticGeneration,
    @DocField(description = "Fingerprint of the imported project model used for this proof.")
    val projectModelFingerprint: AdditionProjectModelFingerprint,
    @DocField(description = "Fingerprint of the compiler classpath used for this proof.")
    val classpathFingerprint: AdditionClasspathFingerprint,
    @DocField(description = "Exact hashes of every workspace file required by this proof.")
    @SerialName("contextFileHashes")
    private val storedContextFileHashes: List<ExactAdditionContextFileHash>,
) {
    val contextFileHashes: List<ExactAdditionContextFileHash>
        get() = Collections.unmodifiableList(storedContextFileHashes)

    init {
        require(storedContextFileHashes == storedContextFileHashes.sortedBy { it.filePath }) {
            "Addition context file hashes must use deterministic path order"
        }
        require(storedContextFileHashes.distinctBy { it.filePath }.size == storedContextFileHashes.size) {
            "Addition context file hashes must contain one hash per path"
        }
    }

    override fun equals(other: Any?): Boolean = other is ExactAdditionProofContext &&
        requiredGeneration == other.requiredGeneration &&
        projectModelFingerprint == other.projectModelFingerprint &&
        classpathFingerprint == other.classpathFingerprint &&
        storedContextFileHashes == other.storedContextFileHashes

    override fun hashCode(): Int = listOf(
        requiredGeneration,
        projectModelFingerprint,
        classpathFingerprint,
        storedContextFileHashes,
    ).hashCode()

    companion object {
        fun of(
            requiredGeneration: MutationSemanticGeneration,
            projectModelFingerprint: AdditionProjectModelFingerprint,
            classpathFingerprint: AdditionClasspathFingerprint,
            contextFileHashes: List<ExactAdditionContextFileHash>,
        ): ExactAdditionProofContext = ExactAdditionProofContext(
            requiredGeneration = requiredGeneration,
            projectModelFingerprint = projectModelFingerprint,
            classpathFingerprint = classpathFingerprint,
            storedContextFileHashes = contextFileHashes.toList().sortedBy { it.filePath },
        )
    }
}

@Serializable
class AdditionWorkspaceRange private constructor(
    @DocField(description = "Normalized absolute path that contains this workspace occurrence.")
    @SerialName("filePath")
    private val storedFilePath: AdditionContextFilePath,
    @DocField(description = "UTF-16 start offset of this workspace occurrence.")
    val startOffset: NonNegativeInt,
    @DocField(description = "UTF-16 end offset of this workspace occurrence.")
    val endOffset: NonNegativeInt,
) {
    val filePath: String
        get() = storedFilePath.value

    init {
        require(endOffset.value > startOffset.value) { "Addition workspace occurrence range must not be empty" }
    }

    override fun equals(other: Any?): Boolean = other is AdditionWorkspaceRange &&
        storedFilePath == other.storedFilePath &&
        startOffset == other.startOffset &&
        endOffset == other.endOffset

    override fun hashCode(): Int = listOf(storedFilePath, startOffset, endOffset).hashCode()

    companion object {
        fun of(filePath: String, startOffset: Int, endOffset: Int): AdditionWorkspaceRange =
            AdditionWorkspaceRange(
                storedFilePath = AdditionContextFilePath(filePath),
                startOffset = NonNegativeInt(startOffset),
                endOffset = NonNegativeInt(endOffset),
            )
    }
}

@Serializable
enum class AdditionRebindingUnresolvedReason {
    NOT_FOUND,
    AMBIGUOUS,
}

@Serializable
sealed interface AdditionRebindingCurrentTarget {
    @Serializable
    @SerialName("RESOLVED")
    class Resolved private constructor(
        @DocField(description = "Exact compiler-resolved target at the existing workspace occurrence.")
        val target: AdditionResolvedTarget,
    ) : AdditionRebindingCurrentTarget {
        override fun equals(other: Any?): Boolean = other is Resolved && target == other.target

        override fun hashCode(): Int = target.hashCode()

        companion object {
            fun of(target: AdditionResolvedTarget): Resolved = Resolved(target)
        }
    }

    @Serializable
    @SerialName("UNRESOLVED")
    class Unresolved private constructor(
        @DocField(description = "Closed reason the existing workspace occurrence has no unique compiler target.")
        val reason: AdditionRebindingUnresolvedReason,
    ) : AdditionRebindingCurrentTarget {
        override fun equals(other: Any?): Boolean = other is Unresolved && reason == other.reason

        override fun hashCode(): Int = reason.hashCode()

        companion object {
            fun of(reason: AdditionRebindingUnresolvedReason): Unresolved = Unresolved(reason)
        }
    }
}

@Serializable
class ExactAdditionRebindingOccurrence private constructor(
    @DocField(description = "Exact workspace range of this rebinding candidate.")
    val range: AdditionWorkspaceRange,
    @DocField(description = "Current compiler binding at this workspace range.")
    val currentTarget: AdditionRebindingCurrentTarget,
    @DocField(description = "Authority that established this rebinding candidate.")
    val provenance: AdditionOccurrenceProvenance,
) {
    override fun equals(other: Any?): Boolean = other is ExactAdditionRebindingOccurrence &&
        range == other.range && currentTarget == other.currentTarget && provenance == other.provenance

    override fun hashCode(): Int = listOf(range, currentTarget, provenance).hashCode()

    companion object {
        fun resolved(
            filePath: String,
            startOffset: Int,
            endOffset: Int,
            target: AdditionResolvedTarget,
        ): ExactAdditionRebindingOccurrence = ExactAdditionRebindingOccurrence(
            range = AdditionWorkspaceRange.of(filePath, startOffset, endOffset),
            currentTarget = AdditionRebindingCurrentTarget.Resolved.of(target),
            provenance = AdditionOccurrenceProvenance.COMPILER,
        )

        fun unresolved(
            filePath: String,
            startOffset: Int,
            endOffset: Int,
            reason: AdditionRebindingUnresolvedReason,
        ): ExactAdditionRebindingOccurrence = ExactAdditionRebindingOccurrence(
            range = AdditionWorkspaceRange.of(filePath, startOffset, endOffset),
            currentTarget = AdditionRebindingCurrentTarget.Unresolved.of(reason),
            provenance = AdditionOccurrenceProvenance.COMPILER,
        )
    }
}

@Serializable
enum class AdditionRebindingDimension {
    EXACT_OCCURRENCE_CARDINALITY,
    COMPLETE_DEPENDENT_SCOPE,
    COMPLETE_IMPLICIT_LOOKUP_SCOPE,
    COMPLETE_JAVA_LOOKUP_SCOPE,
    EVERY_CURRENT_BINDING_CAPTURED,
    VIRTUAL_PROPOSED_BINDINGS_EQUAL_BASELINE,
}

@Serializable
class ExactAdditionRebindingBaseline private constructor(
    @DocField(description = "Exact number of existing rebinding candidates.")
    val cardinality: ExactAdditionCardinality,
    @DocField(description = "Complete closed set of addition rebinding proof dimensions.")
    @SerialName("dimensions")
    private val storedDimensions: List<AdditionRebindingDimension>,
    @DocField(description = "Every current compiler binding that the addition could change.")
    @SerialName("occurrences")
    private val storedOccurrences: List<ExactAdditionRebindingOccurrence>,
) {
    val dimensions: List<AdditionRebindingDimension>
        get() = Collections.unmodifiableList(storedDimensions)

    val occurrences: List<ExactAdditionRebindingOccurrence>
        get() = Collections.unmodifiableList(storedOccurrences)

    init {
        require(storedDimensions == AdditionRebindingDimension.entries) {
            "Exact addition rebinding baseline must prove every closed dimension"
        }
        require(cardinality.value == storedOccurrences.size) {
            "Exact rebinding cardinality must match its occurrence count"
        }
        require(storedOccurrences.all { it.provenance == AdditionOccurrenceProvenance.COMPILER }) {
            "Every exact rebinding occurrence must have compiler provenance"
        }
        require(storedOccurrences == storedOccurrences.sortedWith(rebindingOccurrenceComparator)) {
            "Exact rebinding occurrences must use deterministic source order"
        }
        require(storedOccurrences.map { it.range }.distinct().size == storedOccurrences.size) {
            "Exact rebinding occurrences must have unique source ranges"
        }
        require(storedOccurrences.zipWithNext().all { (left, right) ->
            left.range.filePath != right.range.filePath ||
                left.range.endOffset.value <= right.range.startOffset.value
        }) { "Exact rebinding occurrence ranges must not overlap within one source file" }
    }

    override fun equals(other: Any?): Boolean = other is ExactAdditionRebindingBaseline &&
        cardinality == other.cardinality &&
        storedDimensions == other.storedDimensions &&
        storedOccurrences == other.storedOccurrences

    override fun hashCode(): Int = listOf(cardinality, storedDimensions, storedOccurrences).hashCode()

    companion object {
        fun complete(occurrences: List<ExactAdditionRebindingOccurrence>): ExactAdditionRebindingBaseline {
            val exactOccurrences = occurrences.toList().sortedWith(rebindingOccurrenceComparator)
            return ExactAdditionRebindingBaseline(
                cardinality = ExactAdditionCardinality(exactOccurrences.size),
                storedDimensions = AdditionRebindingDimension.entries,
                storedOccurrences = exactOccurrences,
            )
        }
    }
}

private val rebindingOccurrenceComparator = compareBy<ExactAdditionRebindingOccurrence>(
    { it.range.filePath },
    { it.range.startOffset.value },
    { it.range.endOffset.value },
)

internal fun validateAdditionTargetOwner(
    targetPath: AdditionTargetPath,
    owner: AdditionSourceOwner,
) {
    require(targetPath.toPath() != owner.sourceRoot.toPath() && targetPath.toPath().startsWith(owner.sourceRoot.toPath())) {
        "Addition target must be a strict descendant of its canonical source root"
    }
}

internal fun validateAdditionDeclarations(
    packageIdentity: AdditionKotlinPackage,
    declarations: List<AdditionTopLevelDeclaration>,
) {
    require(declarations.all { it.packageIdentity == packageIdentity }) {
        "Every addition declaration must use the parsed Kotlin package"
    }
    require(declarations == declarations.sortedBy { it.relativeRange.startOffset.value }) {
        "Addition declarations must use deterministic source order"
    }
    require(declarations.zipWithNext().all { (left, right) ->
        left.relativeRange.endOffset.value <= right.relativeRange.startOffset.value
    }) { "Addition declaration ranges must not overlap" }
    require(declarations.map { it.collisionKey }.distinct().size == declarations.size) {
        "Addition declaration collision identities must be unique"
    }
}

internal fun validateAdditionContextCoverage(
    context: ExactAdditionProofContext,
    outboundEvidence: ExactAdditionOutboundEvidence,
    rebindingBaseline: ExactAdditionRebindingBaseline,
) {
    val contextPaths = context.contextFileHashes.mapTo(mutableSetOf()) { it.filePath }
    val requiredPaths = buildSet {
        outboundEvidence.occurrences.mapNotNullTo(this) { it.resolvedTarget.sourceFilePath() }
        rebindingBaseline.occurrences.forEach { occurrence ->
            add(occurrence.range.filePath)
            occurrence.currentTarget.sourceFilePath()?.let(::add)
        }
    }
    require(contextPaths.containsAll(requiredPaths)) {
        "Addition context file hashes must cover every workspace occurrence and source target"
    }
}

internal fun validateZeroAdditionRebindingBaseline(rebindingBaseline: ExactAdditionRebindingBaseline) {
    require(rebindingBaseline.dimensions == AdditionRebindingDimension.entries) {
        "Addition proof rebinding baseline must retain every closed dimension"
    }
    require(rebindingBaseline.cardinality.value == 0 && rebindingBaseline.occurrences.isEmpty()) {
        "Addition proof rebinding baseline must prove zero current rebinding candidates"
    }
}

private fun AdditionResolvedTarget.sourceFilePath(): String? = when (this) {
    is AdditionResolvedTarget.Source -> identity.declarationFile.value
    is AdditionResolvedTarget.External -> null
}

private fun AdditionRebindingCurrentTarget.sourceFilePath(): String? = when (this) {
    is AdditionRebindingCurrentTarget.Resolved -> target.sourceFilePath()
    is AdditionRebindingCurrentTarget.Unresolved -> null
}

private fun requireLowercaseSha256(value: String, label: String) {
    require(value.matches(LOWERCASE_SHA256)) { "$label must be 64 lowercase hexadecimal characters" }
}

private fun requireCanonicalAbsolutePath(value: String, label: String) {
    val path = runCatching { Path.of(value) }.getOrElse {
        throw IllegalArgumentException("$label must be a normalized absolute path", it)
    }
    require(path.isAbsolute && path.normalize().toString() == value) {
        "$label must be a normalized absolute path"
    }
}

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
