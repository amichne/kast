package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable

enum class AddDeclarationCompilerContextFileFailure {
    PATH_NOT_CANONICAL_SOURCE,
    SHA256_INVALID,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationCompilerContextFile private constructor(
    val path: String,
    val sha256: AddDeclarationSha256,
) : Comparable<AddDeclarationCompilerContextFile> {
    override fun compareTo(other: AddDeclarationCompilerContextFile): Int = path.compareTo(other.path)

    companion object {
        /**
         * Proof transition: raw path and hash to Refinement of
         * AddDeclarationCompilerContextFile or AddDeclarationCompilerContextFileFailure.
         *
         * Establishes one canonical absolute Kotlin or Java source path with an exact lowercase
         * SHA-256 identity. The failure type is closed. Raw values may be extracted only at a
         * compiler-context filesystem boundary.
         */
        fun admit(
            path: String,
            sha256: String,
        ): Refinement<AddDeclarationCompilerContextFile, AddDeclarationCompilerContextFileFailure> {
            val canonical = runCatching { Path.of(path) }.getOrNull()
            if (
                canonical == null || !canonical.isAbsolute || canonical.normalize().toString() != path ||
                (!path.endsWith(".kt") && !path.endsWith(".java"))
            ) {
                return Refinement.Rejected(
                    AddDeclarationCompilerContextFileFailure.PATH_NOT_CANONICAL_SOURCE,
                )
            }
            if (!SHA256.matches(sha256)) {
                return Refinement.Rejected(AddDeclarationCompilerContextFileFailure.SHA256_INVALID)
            }
            return Refinement.Refined(
                AddDeclarationCompilerContextFile(path, AddDeclarationSha256.fromProvenRaw(sha256)),
            )
        }
    }
}

enum class AddDeclarationCompilerFingerprintFailure {
    INVALID,
}

@Serializable
@JvmInline
value class AddDeclarationProjectModelFingerprint private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: String to Refinement of AddDeclarationProjectModelFingerprint or
         * AddDeclarationCompilerFingerprintFailure.
         *
         * Establishes one exact lowercase SHA-256 project-model identity. Raw extraction is
         * permitted only at the project-model observation boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<AddDeclarationProjectModelFingerprint, AddDeclarationCompilerFingerprintFailure> =
            if (SHA256.matches(raw)) Refinement.Refined(AddDeclarationProjectModelFingerprint(raw))
            else Refinement.Rejected(AddDeclarationCompilerFingerprintFailure.INVALID)
    }
}

@Serializable
@JvmInline
value class AddDeclarationClasspathFingerprint private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: String to Refinement of AddDeclarationClasspathFingerprint or
         * AddDeclarationCompilerFingerprintFailure.
         *
         * Establishes one exact lowercase SHA-256 compiler-classpath identity. Raw extraction is
         * permitted only at the compiler-classpath observation boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<AddDeclarationClasspathFingerprint, AddDeclarationCompilerFingerprintFailure> =
            if (SHA256.matches(raw)) Refinement.Refined(AddDeclarationClasspathFingerprint(raw))
            else Refinement.Rejected(AddDeclarationCompilerFingerprintFailure.INVALID)
    }
}

enum class AddDeclarationOutboundReferenceCountFailure {
    NEGATIVE,
}

@Serializable
@JvmInline
value class AddDeclarationOutboundReferenceCount private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: Int to Refinement of AddDeclarationOutboundReferenceCount or
         * AddDeclarationOutboundReferenceCountFailure.
         *
         * Establishes the finite exact cardinality of compiler-observed outbound references.
         * Raw extraction is permitted only inside the compiler-backed verification adapter.
         */
        fun parse(
            raw: Int,
        ): Refinement<AddDeclarationOutboundReferenceCount, AddDeclarationOutboundReferenceCountFailure> =
            if (raw >= 0) Refinement.Refined(AddDeclarationOutboundReferenceCount(raw))
            else Refinement.Rejected(AddDeclarationOutboundReferenceCountFailure.NEGATIVE)
    }
}

enum class ExpectedAddDeclarationCompilerContextFailure {
    EMPTY_CONTEXT,
    DUPLICATE_PATH,
}

sealed interface AddDeclarationCompilerContextAdmissionFailure {
    data class ProjectModel(
        val failure: AddDeclarationCompilerFingerprintFailure,
    ) : AddDeclarationCompilerContextAdmissionFailure

    data class Classpath(
        val failure: AddDeclarationCompilerFingerprintFailure,
    ) : AddDeclarationCompilerContextAdmissionFailure

    data class ContextFile(
        val failure: AddDeclarationCompilerContextFileFailure,
    ) : AddDeclarationCompilerContextAdmissionFailure

    data class OutboundReferenceCount(
        val failure: AddDeclarationOutboundReferenceCountFailure,
    ) : AddDeclarationCompilerContextAdmissionFailure

    data class Context(
        val failure: ExpectedAddDeclarationCompilerContextFailure,
    ) : AddDeclarationCompilerContextAdmissionFailure
}

@Serializable
@ConsistentCopyVisibility
data class ExpectedAddDeclarationCompilerContext private constructor(
    val generation: AddDeclarationGeneration,
    val projectModelFingerprint: AddDeclarationProjectModelFingerprint,
    val classpathFingerprint: AddDeclarationClasspathFingerprint,
    val contextFiles: List<AddDeclarationCompilerContextFile>,
    val outboundReferenceCount: AddDeclarationOutboundReferenceCount,
) {
    companion object {
        /**
         * Proof transition: typed compiler observations to Refinement of
         * ExpectedAddDeclarationCompilerContext or ExpectedAddDeclarationCompilerContextFailure.
         *
         * Establishes one G-bound deterministic compiler context with exact source identities and
         * outbound-reference cardinality. It does not establish semantic collision, binding, or
         * diagnostic facts; those require a scoped compiler read. The failure type is closed. Raw
         * extraction is permitted only by the IntelliJ planning and verification adapters.
         */
        fun admit(
            generation: EvidenceGeneration,
            projectModelFingerprint: AddDeclarationProjectModelFingerprint,
            classpathFingerprint: AddDeclarationClasspathFingerprint,
            contextFiles: List<AddDeclarationCompilerContextFile>,
            outboundReferenceCount: AddDeclarationOutboundReferenceCount,
        ): Refinement<ExpectedAddDeclarationCompilerContext, ExpectedAddDeclarationCompilerContextFailure> {
            if (contextFiles.isEmpty()) {
                return Refinement.Rejected(ExpectedAddDeclarationCompilerContextFailure.EMPTY_CONTEXT)
            }
            if (contextFiles.distinctBy(AddDeclarationCompilerContextFile::path).size != contextFiles.size) {
                return Refinement.Rejected(ExpectedAddDeclarationCompilerContextFailure.DUPLICATE_PATH)
            }
            return Refinement.Refined(
                ExpectedAddDeclarationCompilerContext(
                    generation = AddDeclarationGeneration.of(generation),
                    projectModelFingerprint = projectModelFingerprint,
                    classpathFingerprint = classpathFingerprint,
                    contextFiles = contextFiles.sorted(),
                    outboundReferenceCount = outboundReferenceCount,
                ),
            )
        }

        /**
         * Proof transition: one raw source observation to Refinement of
         * ExpectedAddDeclarationCompilerContext or AddDeclarationCompilerContextAdmissionFailure.
         *
         * Establishes the same deterministic context as [admit] for a workspace whose observed
         * context contains exactly one source file. It does not establish completeness outside
         * that supplied context or any semantic proof. The failure type is closed and retains the
         * exact failed refinement. Raw values may be extracted only by a compiler-context
         * observation boundary.
         */
        fun admitSingleSource(
            generation: EvidenceGeneration,
            projectModelFingerprint: String,
            classpathFingerprint: String,
            sourcePath: String,
            sourceSha256: String,
            outboundReferenceCount: Int,
        ): Refinement<ExpectedAddDeclarationCompilerContext, AddDeclarationCompilerContextAdmissionFailure> {
            val model = when (val result = AddDeclarationProjectModelFingerprint.parse(projectModelFingerprint)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AddDeclarationCompilerContextAdmissionFailure.ProjectModel(result.failure),
                )
            }
            val classpath = when (val result = AddDeclarationClasspathFingerprint.parse(classpathFingerprint)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AddDeclarationCompilerContextAdmissionFailure.Classpath(result.failure),
                )
            }
            val source = when (val result = AddDeclarationCompilerContextFile.admit(sourcePath, sourceSha256)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AddDeclarationCompilerContextAdmissionFailure.ContextFile(result.failure),
                )
            }
            val outboundCount = when (
                val result = AddDeclarationOutboundReferenceCount.parse(outboundReferenceCount)
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AddDeclarationCompilerContextAdmissionFailure.OutboundReferenceCount(result.failure),
                )
            }
            return when (
                val result = admit(generation, model, classpath, listOf(source), outboundCount)
            ) {
                is Refinement.Refined -> result
                is Refinement.Rejected -> Refinement.Rejected(
                    AddDeclarationCompilerContextAdmissionFailure.Context(result.failure),
                )
            }
        }
    }
}

private val SHA256 = Regex("[0-9a-f]{64}")
