package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.nio.file.Path
import java.util.Collections

@JvmInline
value class CanonicalRepositoryRoot private constructor(val value: String) {
    init {
        val path = Path.of(value)
        require(value.isNotBlank() && path.isAbsolute && path.normalize() == path)
    }

    internal companion object {
        fun fromValidated(value: String): CanonicalRepositoryRoot = CanonicalRepositoryRoot(value)
    }
}

@JvmInline
value class RepositoryStateIdentity private constructor(val value: String) {
    init {
        require(value.matches(SHA_256_VALUE))
    }

    internal companion object {
        fun fromValidated(value: String): RepositoryStateIdentity = RepositoryStateIdentity(value)
    }
}

class ExactSourceState private constructor(
    val revision: SourceRevision,
    inputs: List<ExactSourceInput>,
) {
    val inputs: List<ExactSourceInput> = immutableList(inputs)

    init {
        require(this.inputs.map { input -> input.path }.distinct().size == this.inputs.size)
    }

    internal companion object {
        fun create(
            revision: SourceRevision,
            inputs: List<ExactSourceInput>,
        ): ExactSourceState = ExactSourceState(
            revision,
            inputs.sortedBy { input -> input.path.value },
        )
    }
}

@JvmInline
value class SourceRevision private constructor(val value: String) {
    init {
        require(value.matches(SOURCE_REVISION_VALUE) && value == value.lowercase())
    }

    internal companion object {
        fun fromValidated(value: String): SourceRevision = SourceRevision(value)
    }
}

sealed interface ExactSourceInput {
    val path: RepositoryRelativePath
    val kind: RawSourceInputKind
    val presence: RawSourceInputPresence
    val disposition: RawSourceInputDisposition
    val contentDigest: SourceContentDigest?

    class IncludedFile private constructor(
        override val path: RepositoryRelativePath,
        override val kind: RawSourceInputKind,
        override val contentDigest: SourceContentDigest,
    ) : ExactSourceInput {
        override val presence: RawSourceInputPresence = RawSourceInputPresence.PRESENT
        override val disposition: RawSourceInputDisposition = RawSourceInputDisposition.INCLUDED

        internal companion object {
            fun create(
                path: RepositoryRelativePath,
                kind: RawSourceInputKind,
                contentDigest: SourceContentDigest,
            ): IncludedFile = IncludedFile(path, kind, contentDigest)
        }
    }

    class ExcludedFile private constructor(
        override val path: RepositoryRelativePath,
        override val kind: RawSourceInputKind,
    ) : ExactSourceInput {
        override val presence: RawSourceInputPresence = RawSourceInputPresence.PRESENT
        override val disposition: RawSourceInputDisposition = RawSourceInputDisposition.EXCLUDED
        override val contentDigest: SourceContentDigest? = null

        internal companion object {
            fun create(
                path: RepositoryRelativePath,
                kind: RawSourceInputKind,
            ): ExcludedFile = ExcludedFile(path, kind)
        }
    }

    class DeletedTrackedInput private constructor(
        override val path: RepositoryRelativePath,
        override val disposition: RawSourceInputDisposition,
    ) : ExactSourceInput {
        override val kind: RawSourceInputKind = RawSourceInputKind.TRACKED_CHANGE
        override val presence: RawSourceInputPresence = RawSourceInputPresence.DELETED
        override val contentDigest: SourceContentDigest? = null

        init {
            require(
                disposition == RawSourceInputDisposition.INCLUDED ||
                    disposition == RawSourceInputDisposition.EXCLUDED,
            )
        }

        internal companion object {
            fun create(
                path: RepositoryRelativePath,
                disposition: RawSourceInputDisposition,
            ): DeletedTrackedInput = DeletedTrackedInput(path, disposition)
        }
    }

    companion object {
        internal fun create(
            path: RepositoryRelativePath,
            kind: RawSourceInputKind,
            presence: RawSourceInputPresence,
            disposition: RawSourceInputDisposition,
            contentDigest: SourceContentDigest?,
        ): ExactSourceInput = when {
            presence == RawSourceInputPresence.PRESENT &&
                disposition == RawSourceInputDisposition.INCLUDED &&
                contentDigest != null -> IncludedFile.create(path, kind, contentDigest)

            presence == RawSourceInputPresence.PRESENT &&
                disposition == RawSourceInputDisposition.EXCLUDED &&
                contentDigest == null -> ExcludedFile.create(path, kind)

            presence == RawSourceInputPresence.DELETED &&
                kind == RawSourceInputKind.TRACKED_CHANGE &&
                contentDigest == null -> DeletedTrackedInput.create(path, disposition)

            else -> error("Source input invariants were not established")
        }
    }
}

@JvmInline
value class RepositoryRelativePath private constructor(val value: String) {
    init {
        val path = Path.of(value)
        require(value.isNotBlank() && !path.isAbsolute && path.normalize() == path)
    }

    internal companion object {
        fun fromValidated(value: String): RepositoryRelativePath = RepositoryRelativePath(value)
    }
}

@JvmInline
value class SourceContentDigest private constructor(val value: String) {
    init {
        require(value.matches(SHA_256_VALUE) && value == value.lowercase())
    }

    internal companion object {
        fun fromValidated(value: String): SourceContentDigest = SourceContentDigest(value)
    }
}

class ResolvedRepositoryScope private constructor(
    compilationUnits: List<AdmittedCompilationUnit>,
) {
    val compilationUnits: List<AdmittedCompilationUnit> = immutableList(compilationUnits)

    init {
        require(this.compilationUnits.isNotEmpty())
        require(this.compilationUnits.map { unit -> unit.id }.distinct().size == this.compilationUnits.size)
    }

    internal companion object {
        fun create(compilationUnits: List<AdmittedCompilationUnit>): ResolvedRepositoryScope =
            ResolvedRepositoryScope(compilationUnits.sortedBy { unit -> unit.id.value })
    }
}

class AdmittedCompilationUnit private constructor(
    val id: CompilationUnitId,
    val moduleIdentity: AdmittedModuleIdentity,
    val moduleName: AdmittedModuleName,
    val sourceSetName: AdmittedSourceSetName,
    val variantName: AdmittedVariantName,
    sourceRoots: List<RepositoryRelativePath>,
    generatedSourceRoots: List<RepositoryRelativePath>,
    declarations: List<AdmittedDeclaration>,
    families: Set<SemanticFamilyName>,
    sourceSetRelationships: Set<SourceSetRelationship>,
    val semanticConfiguration: CoherentSemanticConfiguration,
) {
    val sourceRoots: List<RepositoryRelativePath> = immutableList(sourceRoots)
    val generatedSourceRoots: List<RepositoryRelativePath> = immutableList(generatedSourceRoots)
    val declarations: List<AdmittedDeclaration> = immutableList(declarations)
    val families: Set<SemanticFamilyName> = immutableSet(families)
    val sourceSetRelationships: Set<SourceSetRelationship> = immutableSet(sourceSetRelationships)

    init {
        require(this.sourceRoots.isNotEmpty())
        require(
            this.generatedSourceRoots.all { generatedRoot ->
                this.sourceRoots.any { sourceRoot ->
                    generatedRoot.value == sourceRoot.value ||
                        generatedRoot.value.startsWith("${sourceRoot.value}/")
                }
            },
        )
        require(semanticConfiguration.variantName == variantName)
        require(
            this.declarations.all { declaration ->
                this.sourceRoots.any { sourceRoot ->
                    declaration.path.value == sourceRoot.value ||
                        declaration.path.value.startsWith("${sourceRoot.value}/")
                }
            },
        )
    }

    internal companion object {
        fun create(
            id: CompilationUnitId,
            moduleIdentity: AdmittedModuleIdentity,
            moduleName: AdmittedModuleName,
            sourceSetName: AdmittedSourceSetName,
            variantName: AdmittedVariantName,
            sourceRoots: List<RepositoryRelativePath>,
            generatedSourceRoots: List<RepositoryRelativePath>,
            declarations: List<AdmittedDeclaration>,
            families: Set<SemanticFamilyName>,
            sourceSetRelationships: Set<SourceSetRelationship>,
            semanticConfiguration: CoherentSemanticConfiguration,
        ): AdmittedCompilationUnit = AdmittedCompilationUnit(
            id = id,
            moduleIdentity = moduleIdentity,
            moduleName = moduleName,
            sourceSetName = sourceSetName,
            variantName = variantName,
            sourceRoots = sourceRoots.distinct().sortedBy(RepositoryRelativePath::value),
            generatedSourceRoots = generatedSourceRoots.distinct().sortedBy(RepositoryRelativePath::value),
            declarations = declarations.sortedWith(
                compareBy({ declaration -> declaration.name.value }, { declaration -> declaration.path.value }),
            ),
            families = families.sortedBy(SemanticFamilyName::value).toCollection(linkedSetOf()),
            sourceSetRelationships = sourceSetRelationships.sortedWith(
                compareBy(
                    { relationship -> relationship.kind.name },
                    { relationship -> relationship.targetCompilationUnitId.value },
                ),
            ).toCollection(linkedSetOf()),
            semanticConfiguration = semanticConfiguration,
        )
    }
}

class AdmittedDeclaration private constructor(
    val name: AdmittedDeclarationName,
    val path: RepositoryRelativePath,
) {
    internal companion object {
        fun create(
            name: AdmittedDeclarationName,
            path: RepositoryRelativePath,
        ): AdmittedDeclaration = AdmittedDeclaration(name, path)
    }
}

class SourceSetRelationship private constructor(
    val kind: SourceSetRelationshipKind,
    val targetCompilationUnitId: CompilationUnitId,
) {
    internal companion object {
        fun create(
            kind: SourceSetRelationshipKind,
            targetCompilationUnitId: CompilationUnitId,
        ): SourceSetRelationship = SourceSetRelationship(kind, targetCompilationUnitId)
    }

    override fun equals(other: Any?): Boolean =
        other is SourceSetRelationship &&
            kind == other.kind &&
            targetCompilationUnitId == other.targetCompilationUnitId

    override fun hashCode(): Int = 31 * kind.hashCode() + targetCompilationUnitId.hashCode()
}

@JvmInline value class CompilationUnitId private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilationUnitId(value) }
}

@JvmInline value class AdmittedModuleName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedModuleName(value) }
}

@JvmInline value class AdmittedModuleIdentity private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedModuleIdentity(value) }
}

@JvmInline value class AdmittedSourceSetName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedSourceSetName(value) }
}

@JvmInline value class AdmittedVariantName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedVariantName(value) }
}

@JvmInline value class AdmittedDeclarationName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedDeclarationName(value) }
}

@JvmInline value class SemanticFamilyName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = SemanticFamilyName(value) }
}

private val SOURCE_REVISION_VALUE: Regex = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
private val SHA_256_VALUE: Regex = Regex("[0-9a-f]{64}")

private fun <Value> immutableList(values: Collection<Value>): List<Value> =
    Collections.unmodifiableList(values.toList())

private fun <Value> immutableSet(values: Collection<Value>): Set<Value> =
    Collections.unmodifiableSet(LinkedHashSet(values))
