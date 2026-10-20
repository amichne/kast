package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

enum class LibraryModelBindingFailure {
    INVALID_DIGEST,
    INVALID_ARTIFACT_VERSION,
    INVALID_MODULE_NAME,
    BASIS_MISMATCH,
    NOT_LIBRARY,
    CALLABLE_MISMATCH,
    ARTIFACT_MISMATCH,
    VERSION_MISMATCH,
    DECLARATION_OUTSIDE_ARTIFACT,
}

/** Content identity for an entire reviewed library artifact, independent of its installation path. */
@JvmInline
value class LibraryArtifactDigest private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<LibraryArtifactDigest, LibraryModelBindingFailure> =
            if (value.length == LIBRARY_ARTIFACT_DIGEST_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(LibraryArtifactDigest(value))
            else Refinement.Rejected(LibraryModelBindingFailure.INVALID_DIGEST)
    }
}

@JvmInline
value class LibraryArtifactVersion private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<LibraryArtifactVersion, LibraryModelBindingFailure> =
            if (value.isLibraryMetadataLabel()) Refinement.Refined(LibraryArtifactVersion(value))
            else Refinement.Rejected(LibraryModelBindingFailure.INVALID_ARTIFACT_VERSION)
    }
}

@JvmInline
value class LibraryModelModuleName private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<LibraryModelModuleName, LibraryModelBindingFailure> =
            if (value.isLibraryMetadataLabel()) Refinement.Refined(LibraryModelModuleName(value))
            else Refinement.Rejected(LibraryModelBindingFailure.INVALID_MODULE_NAME)
    }
}

data class ReviewedLibraryArtifact(
    val binaryDigest: LibraryArtifactDigest,
    val sourceDigest: LibraryArtifactDigest,
    val version: LibraryArtifactVersion,
)

enum class LibraryModelDeclarationOrigin {
    SOURCE,
    LIBRARY,
    UNSUPPORTED,
}

/** Native observations describe actual module bytes. They do not assert that a model applies. */
data class LibraryArtifactObservation(
    val basis: SemanticReadIdentity,
    val root: DetachedVirtualFileUrl,
    val moduleName: LibraryModelModuleName,
    val binaryDigest: LibraryArtifactDigest,
    val version: LibraryArtifactVersion,
    val origin: LibraryModelDeclarationOrigin,
)

/** Automatic value models remain separate from supplied representation-state rules. */
sealed interface ReviewedValueModel {
    val reference: ModelRuleReference
    val artifact: ReviewedLibraryArtifact
    val callable: CanonicalCompilerSignature.Function

    data object FirstOrNull : ReviewedValueModel {
        override val reference =
            ModelRuleReference(
                ContractModelIdentity(
                    modelLiteral("kotlin-stdlib-value-flow"),
                    (ModelVersion.parse(1) as Refinement.Refined).value,
                    modelLiteral("source-sha256:5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"),
                ),
                modelLiteral("collections.list.firstOrNull.select-zero"),
            )
        override val artifact =
            ReviewedLibraryArtifact(
                digestLiteral("0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"),
                digestLiteral("5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"),
                (LibraryArtifactVersion.parse("2.3.20-release-208") as Refinement.Refined).value,
            )
        override val callable =
            (CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.collections.firstOrNull",
                    rawReceiverType = "kotlin/collections/List<T>",
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 1,
                ) as Refinement.Refined)
                .value as CanonicalCompilerSignature.Function
    }

    data object Let : ReviewedValueModel {
        override val reference =
            ModelRuleReference(FirstOrNull.reference.model, modelLiteral("scope.let.invoke-block-value"))
        override val artifact = FirstOrNull.artifact
        override val callable =
            (CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.let",
                    rawReceiverType = "T",
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("(T)->R"),
                    rawTypeParameterCount = LET_TYPE_PARAMETER_COUNT,
                ) as Refinement.Refined)
                .value as CanonicalCompilerSignature.Function
    }

    data object SingleElementListOf : ReviewedValueModel {
        override val reference =
            ModelRuleReference(FirstOrNull.reference.model, modelLiteral("collections.listOf.insert-zero"))
        override val artifact = FirstOrNull.artifact
        override val callable =
            (CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.collections.listOf",
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("T"),
                    rawTypeParameterCount = 1,
                ) as Refinement.Refined)
                .value as CanonicalCompilerSignature.Function
    }
}

/** Exact compiler call, module membership, observed bytes, and reviewed contract stay together. */
class ReviewedLibraryBinding
private constructor(
    val invocation: ValueInvocation,
    val model: ReviewedValueModel,
    val artifact: LibraryArtifactObservation,
) {
    fun matchesInvocation(other: ValueInvocation): Boolean =
        invocation == other &&
            invocation.enclosing == other.enclosing &&
            invocation.callable == other.callable &&
            invocation.executionOwner.enclosing == other.executionOwner.enclosing &&
            invocation.executionOwner.body == other.executionOwner.body

    override fun equals(other: Any?): Boolean =
        other is ReviewedLibraryBinding &&
            matchesInvocation(other.invocation) &&
            model == other.model &&
            artifact == other.artifact

    override fun hashCode(): Int {
        var result = invocation.hashCode()
        result = 31 * result + invocation.enclosing.hashCode()
        result = 31 * result + invocation.callable.hashCode()
        result = 31 * result + invocation.executionOwner.enclosing.hashCode()
        result = 31 * result + invocation.executionOwner.body.hashCode()
        result = 31 * result + model.hashCode()
        return 31 * result + artifact.hashCode()
    }

    companion object {
        fun fromCompiler(
            invocation: ValueInvocation,
            model: ReviewedValueModel,
            artifact: LibraryArtifactObservation,
        ): Refinement<ReviewedLibraryBinding, LibraryModelBindingFailure> {
            if (artifact.basis != invocation.basis)
                return Refinement.Rejected(LibraryModelBindingFailure.BASIS_MISMATCH)
            if (artifact.origin != LibraryModelDeclarationOrigin.LIBRARY)
                return Refinement.Rejected(LibraryModelBindingFailure.NOT_LIBRARY)
            if (invocation.signature != model.callable)
                return Refinement.Rejected(LibraryModelBindingFailure.CALLABLE_MISMATCH)
            if (artifact.binaryDigest != model.artifact.binaryDigest)
                return Refinement.Rejected(LibraryModelBindingFailure.ARTIFACT_MISMATCH)
            if (artifact.version != model.artifact.version)
                return Refinement.Rejected(LibraryModelBindingFailure.VERSION_MISMATCH)
            val file =
                invocation.callable.file as? SymbolDiscoveryFileIdentity.External
                    ?: return Refinement.Rejected(LibraryModelBindingFailure.DECLARATION_OUTSIDE_ARTIFACT)
            if (
                !artifact.root.value.startsWith("jar://") ||
                    !artifact.root.value.endsWith("!/") ||
                    !file.url.value.startsWith(artifact.root.value)
            )
                return Refinement.Rejected(LibraryModelBindingFailure.DECLARATION_OUTSIDE_ARTIFACT)
            return Refinement.Refined(ReviewedLibraryBinding(invocation, model, artifact))
        }
    }
}

private fun modelLiteral(value: String): ModelIdentifier = (ModelIdentifier.parse(value) as Refinement.Refined).value

private fun digestLiteral(value: String): LibraryArtifactDigest =
    (LibraryArtifactDigest.parse(value) as Refinement.Refined).value

private const val LIBRARY_ARTIFACT_DIGEST_HEX_LENGTH = 64
private const val LIBRARY_METADATA_LABEL_MAX_LENGTH = 256
private const val LET_TYPE_PARAMETER_COUNT = 2

private fun String.isLibraryMetadataLabel(): Boolean =
    isNotBlank() && length <= LIBRARY_METADATA_LABEL_MAX_LENGTH && none { it.isISOControl() }
