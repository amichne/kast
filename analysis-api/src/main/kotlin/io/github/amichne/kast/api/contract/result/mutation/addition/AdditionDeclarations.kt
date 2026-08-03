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
    @DocField(description = "UTF-16 start offset relative to the proposed addition text.")
    val startOffset: NonNegativeInt,
    @DocField(description = "UTF-16 end offset relative to the proposed addition text.")
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
    @DocField(description = "Parsed Kotlin package of this top-level declaration.")
    val packageIdentity: AdditionKotlinPackage,
    @DocField(description = "Compiler-observed declaration name.")
    @SerialName("name")
    private val storedName: AdditionDeclarationName,
    @DocField(description = "Closed Kotlin top-level declaration kind.")
    val kind: AdditionTopLevelDeclarationKind,
    @DocField(description = "Exact declaration range relative to the proposed addition text.")
    val relativeRange: AdditionRelativeRange,
    @DocField(description = "Compiler-derived signature used for collision checks.")
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
    class Source private constructor(
        @DocField(description = "Exact compiler-resolved source declaration identity.")
        val identity: SymbolIdentity,
    ) : AdditionResolvedTarget {
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
        @DocField(description = "Compiler-provided fully-qualified external declaration name.")
        val fqName: String,
        @DocField(description = "Compiler-provided external declaration kind.")
        val kind: SymbolKind,
        @DocField(description = "Canonical compiler signature that identifies the external declaration.")
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
    @DocField(description = "Exact source range of this outbound reference in the proposed addition.")
    val range: AdditionRelativeRange,
    @DocField(description = "Compiler-resolved target of this outbound reference.")
    val resolvedTarget: AdditionResolvedTarget,
    @DocField(description = "Authority that established this outbound reference.")
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
    @DocField(description = "Exact number of outbound references in the proposed addition.")
    val cardinality: ExactAdditionCardinality,
    @DocField(description = "Every compiler-resolved outbound reference in source order.")
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
    @DocField(description = "Exact number of declarations checked for collisions.")
    val declarationCardinality: ExactAdditionCardinality,
    @DocField(description = "Complete closed set of addition collision proof dimensions.")
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

private fun requireCanonicalNonBlank(value: String, label: String) {
    require(value.isNotBlank() && value == value.trim()) { "$label must be canonical and non-blank" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}
