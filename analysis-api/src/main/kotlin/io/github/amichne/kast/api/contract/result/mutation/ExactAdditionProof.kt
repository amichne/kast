package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import java.nio.file.Path
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class AdditionProjectModelFingerprint private constructor(val value: String) {
    init {
        requireLowercaseSha256(value, "Addition project-model fingerprint")
    }

    companion object {
        fun of(value: String): AdditionProjectModelFingerprint = AdditionProjectModelFingerprint(value)
    }
}

@Serializable
@JvmInline
value class AdditionClasspathFingerprint private constructor(val value: String) {
    init {
        requireLowercaseSha256(value, "Addition classpath fingerprint")
    }

    companion object {
        fun of(value: String): AdditionClasspathFingerprint = AdditionClasspathFingerprint(value)
    }
}

@Serializable
@JvmInline
value class AdditionDeclarationCollisionSignature private constructor(val value: String) {
    init {
        requireLowercaseSha256(value, "Addition declaration collision signature")
    }

    companion object {
        fun of(value: String): AdditionDeclarationCollisionSignature = AdditionDeclarationCollisionSignature(value)
    }
}

@Serializable
@JvmInline
value class AdditionPostimageSha256 private constructor(val value: String) {
    init {
        requireLowercaseSha256(value, "Addition postimage SHA-256")
    }

    companion object {
        fun of(value: String): AdditionPostimageSha256 = AdditionPostimageSha256(value)
    }
}

@Serializable
@JvmInline
value class AdditionTargetPreimageSha256 private constructor(val value: String) {
    init {
        requireLowercaseSha256(value, "Addition target preimage SHA-256")
    }

    companion object {
        fun of(value: String): AdditionTargetPreimageSha256 = AdditionTargetPreimageSha256(value)
    }
}

@Serializable
@JvmInline
value class AdditionTargetPath private constructor(val value: String) {
    init {
        requireCanonicalAbsolutePath(value, "Addition target path")
        require(value.endsWith(".kt") && !value.endsWith(".kts")) {
            "Addition target path must name one Kotlin source file"
        }
    }

    internal fun toPath(): Path = Path.of(value)

    companion object {
        fun parse(value: String): AdditionTargetPath = AdditionTargetPath(value)
    }
}

@Serializable
@JvmInline
value class AdditionSourceRoot private constructor(val value: String) {
    init {
        requireCanonicalAbsolutePath(value, "Addition source root")
    }

    internal fun toPath(): Path = Path.of(value)

    companion object {
        fun parse(value: String): AdditionSourceRoot = AdditionSourceRoot(value)
    }
}

@Serializable
@JvmInline
value class AdditionGradleBuildRoot private constructor(val value: String) {
    init {
        requireCanonicalAbsolutePath(value, "Addition Gradle build root")
    }

    internal fun toPath(): Path = Path.of(value)

    companion object {
        fun parse(value: String): AdditionGradleBuildRoot = AdditionGradleBuildRoot(value)
    }
}

@Serializable
@JvmInline
value class AdditionIdeaModuleName private constructor(val value: String) {
    init {
        requireCanonicalNonBlank(value, "Addition IDEA module name")
    }

    companion object {
        fun of(value: String): AdditionIdeaModuleName = AdditionIdeaModuleName(value)
    }
}

@Serializable
@JvmInline
value class AdditionGradleProjectPath private constructor(val value: String) {
    init {
        require(value.startsWith(':')) { "Addition Gradle project path must be absolute" }
        require(value.none(Char::isISOControl)) { "Addition Gradle project path must not contain control characters" }
        require('/' !in value && '\\' !in value) { "Addition Gradle project path must use colon segments" }
        require(value == ":" || (!value.endsWith(':') && value.drop(1).split(':').all(String::isNotBlank))) {
            "Addition Gradle project path must not contain empty segments"
        }
    }

    companion object {
        fun parse(value: String): AdditionGradleProjectPath = AdditionGradleProjectPath(value)
    }
}

@Serializable
@JvmInline
value class AdditionGradleSourceSetName private constructor(val value: String) {
    init {
        requireCanonicalNonBlank(value, "Addition Gradle source-set name")
        require('/' !in value && '\\' !in value && ':' !in value) {
            "Addition Gradle source-set name must be one model-owned name"
        }
    }

    companion object {
        fun of(value: String): AdditionGradleSourceSetName = AdditionGradleSourceSetName(value)
    }
}

@Serializable
class AdditionSourceOwner private constructor(
    val sourceRoot: AdditionSourceRoot,
    val ideaModuleName: AdditionIdeaModuleName,
    val gradleBuildRoot: AdditionGradleBuildRoot,
    val gradleProjectPath: AdditionGradleProjectPath,
    val sourceSetName: AdditionGradleSourceSetName,
) {
    init {
        require(sourceRoot.toPath() != gradleBuildRoot.toPath() && sourceRoot.toPath().startsWith(gradleBuildRoot.toPath())) {
            "Addition source root must be a strict descendant of its Gradle build root"
        }
    }

    override fun equals(other: Any?): Boolean = other is AdditionSourceOwner &&
        sourceRoot == other.sourceRoot &&
        ideaModuleName == other.ideaModuleName &&
        gradleBuildRoot == other.gradleBuildRoot &&
        gradleProjectPath == other.gradleProjectPath &&
        sourceSetName == other.sourceSetName

    override fun hashCode(): Int = listOf(
        sourceRoot,
        ideaModuleName,
        gradleBuildRoot,
        gradleProjectPath,
        sourceSetName,
    ).hashCode()

    companion object {
        fun of(
            sourceRoot: AdditionSourceRoot,
            ideaModuleName: AdditionIdeaModuleName,
            gradleBuildRoot: AdditionGradleBuildRoot,
            gradleProjectPath: AdditionGradleProjectPath,
            sourceSetName: AdditionGradleSourceSetName,
        ): AdditionSourceOwner = AdditionSourceOwner(
            sourceRoot = sourceRoot,
            ideaModuleName = ideaModuleName,
            gradleBuildRoot = gradleBuildRoot,
            gradleProjectPath = gradleProjectPath,
            sourceSetName = sourceSetName,
        )
    }
}

@Serializable
sealed interface AdditionKotlinPackage {
    @Serializable
    @SerialName("ROOT")
    data object Root : AdditionKotlinPackage

    @Serializable
    @SerialName("NAMED")
    class Named private constructor(
        @SerialName("segments")
        private val storedSegments: List<AdditionKotlinPackageSegment>,
    ) : AdditionKotlinPackage {
        val segments: List<AdditionKotlinPackageSegment>
            get() = Collections.unmodifiableList(storedSegments)

        init {
            require(storedSegments.isNotEmpty()) { "Named Kotlin package must contain at least one segment" }
        }

        override fun equals(other: Any?): Boolean = other is Named && storedSegments == other.storedSegments

        override fun hashCode(): Int = storedSegments.hashCode()

        companion object {
            fun of(vararg segments: String): Named = Named(
                storedSegments = segments.map(AdditionKotlinPackageSegment::of),
            )
        }
    }
}

@Serializable
@JvmInline
value class AdditionKotlinPackageSegment private constructor(val value: String) {
    init {
        require(value.isNotEmpty()) { "Kotlin package segment must not be empty" }
        require(value.none(Char::isISOControl)) { "Kotlin package segment must not contain control characters" }
    }

    companion object {
        fun of(value: String): AdditionKotlinPackageSegment = AdditionKotlinPackageSegment(value)
    }
}

@Serializable
enum class AdditionTopLevelDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ANNOTATION_CLASS,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

@Serializable
@JvmInline
private value class AdditionDeclarationName(val value: String) {
    init {
        require(value.isNotEmpty()) { "Addition declaration name must not be empty" }
        require(value.none(Char::isISOControl)) { "Addition declaration name must not contain control characters" }
    }
}

@Serializable
class AdditionRelativeRange private constructor(
    val startOffset: NonNegativeInt,
    val endOffset: NonNegativeInt,
) {
    init {
        require(endOffset.value > startOffset.value) { "Addition source range must not be empty" }
    }

    override fun equals(other: Any?): Boolean = other is AdditionRelativeRange &&
        startOffset == other.startOffset && endOffset == other.endOffset

    override fun hashCode(): Int = 31 * startOffset.hashCode() + endOffset.hashCode()

    companion object {
        fun of(startOffset: Int, endOffset: Int): AdditionRelativeRange = AdditionRelativeRange(
            startOffset = NonNegativeInt(startOffset),
            endOffset = NonNegativeInt(endOffset),
        )
    }
}

@Serializable
class AdditionTopLevelDeclaration private constructor(
    val packageIdentity: AdditionKotlinPackage,
    @SerialName("name")
    private val storedName: AdditionDeclarationName,
    val kind: AdditionTopLevelDeclarationKind,
    val relativeRange: AdditionRelativeRange,
    val collisionSignature: AdditionDeclarationCollisionSignature,
) {
    val name: String
        get() = storedName.value

    internal val collisionKey: List<Any>
        get() = when (kind) {
            AdditionTopLevelDeclarationKind.CLASS,
            AdditionTopLevelDeclarationKind.INTERFACE,
            AdditionTopLevelDeclarationKind.OBJECT,
            AdditionTopLevelDeclarationKind.ENUM_CLASS,
            AdditionTopLevelDeclarationKind.ANNOTATION_CLASS,
            AdditionTopLevelDeclarationKind.TYPE_ALIAS,
            -> listOf(packageIdentity, storedName, "CLASSIFIER")

            AdditionTopLevelDeclarationKind.FUNCTION,
            AdditionTopLevelDeclarationKind.PROPERTY,
            -> listOf(packageIdentity, storedName, kind, collisionSignature)
        }

    override fun equals(other: Any?): Boolean = other is AdditionTopLevelDeclaration &&
        packageIdentity == other.packageIdentity &&
        storedName == other.storedName &&
        kind == other.kind &&
        relativeRange == other.relativeRange &&
        collisionSignature == other.collisionSignature

    override fun hashCode(): Int = listOf(
        packageIdentity,
        storedName,
        kind,
        relativeRange,
        collisionSignature,
    ).hashCode()

    companion object {
        fun of(
            packageIdentity: AdditionKotlinPackage,
            name: String,
            kind: AdditionTopLevelDeclarationKind,
            relativeStartOffset: Int,
            relativeEndOffset: Int,
            collisionSignature: AdditionDeclarationCollisionSignature,
        ): AdditionTopLevelDeclaration = AdditionTopLevelDeclaration(
            packageIdentity = packageIdentity,
            storedName = AdditionDeclarationName(name),
            kind = kind,
            relativeRange = AdditionRelativeRange.of(relativeStartOffset, relativeEndOffset),
            collisionSignature = collisionSignature,
        )
    }
}

@Serializable
@JvmInline
value class AdditionCompilerTargetSignature private constructor(val value: String) {
    init {
        requireCanonicalNonBlank(value, "Addition compiler target signature")
    }

    companion object {
        fun of(value: String): AdditionCompilerTargetSignature = AdditionCompilerTargetSignature(value)
    }
}

@Serializable
sealed interface AdditionResolvedTarget {
    @Serializable
    @SerialName("SOURCE")
    class Source private constructor(val identity: SymbolIdentity) : AdditionResolvedTarget {
        init {
            require(identity.kind != SymbolKind.UNKNOWN) { "Addition source target kind must be compiler-known" }
        }

        override fun equals(other: Any?): Boolean = other is Source && identity == other.identity

        override fun hashCode(): Int = identity.hashCode()

        companion object {
            fun of(identity: SymbolIdentity): Source = Source(identity)
        }
    }

    @Serializable
    @SerialName("EXTERNAL")
    class External private constructor(
        val fqName: String,
        val kind: SymbolKind,
        val compilerSignature: AdditionCompilerTargetSignature,
    ) : AdditionResolvedTarget {
        init {
            requireCanonicalNonBlank(fqName, "Addition external target FQ name")
            require(kind != SymbolKind.UNKNOWN) { "Addition external target kind must be compiler-known" }
        }

        override fun equals(other: Any?): Boolean = other is External &&
            fqName == other.fqName && kind == other.kind && compilerSignature == other.compilerSignature

        override fun hashCode(): Int = listOf(fqName, kind, compilerSignature).hashCode()

        companion object {
            fun of(
                fqName: String,
                kind: SymbolKind,
                compilerSignature: AdditionCompilerTargetSignature,
            ): External = External(fqName, kind, compilerSignature)
        }
    }
}

@Serializable
enum class AdditionOccurrenceProvenance {
    COMPILER,
}

@Serializable
class ExactAdditionOutboundOccurrence private constructor(
    val range: AdditionRelativeRange,
    val resolvedTarget: AdditionResolvedTarget,
    val provenance: AdditionOccurrenceProvenance,
) {
    override fun equals(other: Any?): Boolean = other is ExactAdditionOutboundOccurrence &&
        range == other.range && resolvedTarget == other.resolvedTarget && provenance == other.provenance

    override fun hashCode(): Int = listOf(range, resolvedTarget, provenance).hashCode()

    companion object {
        fun of(
            relativeStartOffset: Int,
            relativeEndOffset: Int,
            resolvedTarget: AdditionResolvedTarget,
        ): ExactAdditionOutboundOccurrence = ExactAdditionOutboundOccurrence(
            range = AdditionRelativeRange.of(relativeStartOffset, relativeEndOffset),
            resolvedTarget = resolvedTarget,
            provenance = AdditionOccurrenceProvenance.COMPILER,
        )
    }
}

@Serializable
@JvmInline
value class ExactAdditionCardinality(val value: Int) {
    init {
        require(value >= 0) { "Exact addition cardinality must be non-negative" }
    }
}

@Serializable
class ExactAdditionOutboundEvidence private constructor(
    val cardinality: ExactAdditionCardinality,
    @SerialName("occurrences")
    private val storedOccurrences: List<ExactAdditionOutboundOccurrence>,
) {
    val occurrences: List<ExactAdditionOutboundOccurrence>
        get() = Collections.unmodifiableList(storedOccurrences)

    init {
        require(cardinality.value == storedOccurrences.size) {
            "Exact outbound cardinality must match its occurrence count"
        }
        require(storedOccurrences.all { it.provenance == AdditionOccurrenceProvenance.COMPILER }) {
            "Every exact outbound occurrence must have compiler provenance"
        }
        require(storedOccurrences == storedOccurrences.sortedBy { it.range.startOffset.value }) {
            "Exact outbound occurrences must use deterministic source order"
        }
        require(storedOccurrences.map { it.range }.distinct().size == storedOccurrences.size) {
            "Exact outbound occurrences must have unique ranges"
        }
        require(storedOccurrences.zipWithNext().all { (left, right) ->
            left.range.endOffset.value <= right.range.startOffset.value
        }) { "Exact outbound occurrence ranges must not overlap" }
    }

    override fun equals(other: Any?): Boolean = other is ExactAdditionOutboundEvidence &&
        cardinality == other.cardinality && storedOccurrences == other.storedOccurrences

    override fun hashCode(): Int = 31 * cardinality.hashCode() + storedOccurrences.hashCode()

    companion object {
        fun complete(occurrences: List<ExactAdditionOutboundOccurrence>): ExactAdditionOutboundEvidence {
            val exactOccurrences = occurrences.toList().sortedWith(outboundOccurrenceComparator)
            return ExactAdditionOutboundEvidence(
                cardinality = ExactAdditionCardinality(exactOccurrences.size),
                storedOccurrences = exactOccurrences,
            )
        }
    }
}

private val outboundOccurrenceComparator = compareBy<ExactAdditionOutboundOccurrence>(
    { it.range.startOffset.value },
    { it.range.endOffset.value },
)

@Serializable
enum class AdditionCollisionDimension {
    EXACT_DECLARATION_IDENTITIES,
    COMPLETE_OWNING_SOURCE_SCOPE,
    COMPLETE_DEPENDENT_SCOPE,
    NO_COMPILER_COLLISION,
}

@Serializable
class ExactAdditionCollisionEvidence private constructor(
    val declarationCardinality: ExactAdditionCardinality,
    @SerialName("dimensions")
    private val storedDimensions: List<AdditionCollisionDimension>,
) {
    val dimensions: List<AdditionCollisionDimension>
        get() = Collections.unmodifiableList(storedDimensions)

    init {
        require(storedDimensions == AdditionCollisionDimension.entries) {
            "Exact addition collision evidence must prove every closed dimension"
        }
    }

    override fun equals(other: Any?): Boolean = other is ExactAdditionCollisionEvidence &&
        declarationCardinality == other.declarationCardinality && storedDimensions == other.storedDimensions

    override fun hashCode(): Int = 31 * declarationCardinality.hashCode() + storedDimensions.hashCode()

    companion object {
        fun complete(declarationCount: Int): ExactAdditionCollisionEvidence = ExactAdditionCollisionEvidence(
            declarationCardinality = ExactAdditionCardinality(declarationCount),
            storedDimensions = AdditionCollisionDimension.entries,
        )
    }
}

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
    @SerialName("filePath")
    private val storedFilePath: AdditionContextFilePath,
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
    val requiredGeneration: MutationSemanticGeneration,
    val projectModelFingerprint: AdditionProjectModelFingerprint,
    val classpathFingerprint: AdditionClasspathFingerprint,
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
    @SerialName("filePath")
    private val storedFilePath: AdditionContextFilePath,
    val startOffset: NonNegativeInt,
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
    class Resolved private constructor(val target: AdditionResolvedTarget) : AdditionRebindingCurrentTarget {
        override fun equals(other: Any?): Boolean = other is Resolved && target == other.target

        override fun hashCode(): Int = target.hashCode()

        companion object {
            fun of(target: AdditionResolvedTarget): Resolved = Resolved(target)
        }
    }

    @Serializable
    @SerialName("UNRESOLVED")
    class Unresolved private constructor(
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
    val range: AdditionWorkspaceRange,
    val currentTarget: AdditionRebindingCurrentTarget,
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
    val cardinality: ExactAdditionCardinality,
    @SerialName("dimensions")
    private val storedDimensions: List<AdditionRebindingDimension>,
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

private fun requireCanonicalNonBlank(value: String, label: String) {
    require(value.isNotBlank() && value == value.trim()) { "$label must be canonical and non-blank" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
