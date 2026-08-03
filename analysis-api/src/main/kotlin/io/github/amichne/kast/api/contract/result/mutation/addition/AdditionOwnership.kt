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
    @DocField(description = "Canonical source root that owns the addition target.")
    val sourceRoot: AdditionSourceRoot,
    @DocField(description = "IDEA module that owns the source root.")
    val ideaModuleName: AdditionIdeaModuleName,
    @DocField(description = "Canonical Gradle build root that contains the source root.")
    val gradleBuildRoot: AdditionGradleBuildRoot,
    @DocField(description = "Absolute Gradle project path that owns the source root.")
    val gradleProjectPath: AdditionGradleProjectPath,
    @DocField(description = "Gradle source-set name that owns the source root.")
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
        @DocField(description = "Validated Kotlin package segments in source order.")
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
