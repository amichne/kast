package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReplacementOccurrenceProvenance {
    COMPILER,
}

@Serializable
enum class ReplacementCompilerSymbolKind {
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    CLASS,
    TYPE_ALIAS,
    PARAMETER,
    TYPE_PARAMETER,
    PACKAGE,
}

@Serializable
@JvmInline
value class ReplacementCompilerTargetSignature(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Replacement compiler target signature must not be blank" }
    }
}

@Serializable
enum class ReplacementVisibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PACKAGE_PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE,
    LOCAL,
}

@Serializable
enum class ReplacementModality {
    FINAL,
    SEALED,
    OPEN,
    ABSTRACT,
}

@Serializable
enum class ReplacementTypeVariance {
    INVARIANT,
    IN,
    OUT,
}

@Serializable
enum class ReplacementProofDimension {
    EXACT_TARGET_IDENTITY,
    SUPPORTED_TARGET_KIND,
    SINGLE_SUPPORTED_PROPOSED_DECLARATION,
    COMPILER_SIGNATURE_EQUAL,
    PROPOSED_PSI_TRAVERSAL_EXHAUSTIVE,
    EVERY_REFERENCE_COMPILER_RESOLVED,
    EVERY_REFERENCE_TARGET_MATCHED,
    EVERY_CALL_EXACT,
    NO_UNSUPPORTED_REFERENCE_KIND,
    EXACT_OUTBOUND_CARDINALITY,
    SOURCE_CONTEXT_HASH_BOUND,
    SEMANTIC_GENERATION_UNCHANGED,
}

@Serializable
sealed interface ReplacementOutboundEvidence {
    val dimensions: List<ReplacementProofDimension>

    @Serializable
    @SerialName("complete")
    class Complete private constructor(
        val cardinality: ResultCardinality.Exact,
        @SerialName("dimensions")
        private val storedDimensions: List<ReplacementProofDimension>,
    ) : ReplacementOutboundEvidence {
        override val dimensions: List<ReplacementProofDimension>
            get() = Collections.unmodifiableList(storedDimensions)

        init {
            require(storedDimensions == ReplacementProofDimension.entries) {
                "Complete replacement evidence must prove every closed dimension"
            }
        }

        override fun equals(other: Any?): Boolean = other is Complete &&
            cardinality == other.cardinality && storedDimensions == other.storedDimensions

        override fun hashCode(): Int = 31 * cardinality.hashCode() + storedDimensions.hashCode()

        override fun toString(): String =
            "ReplacementOutboundEvidence.Complete(cardinality=$cardinality, dimensions=$storedDimensions)"

        companion object {
            fun of(exactOccurrenceCount: Int): Complete = Complete(
                cardinality = ResultCardinality.Exact(exactOccurrenceCount),
                storedDimensions = ReplacementProofDimension.entries.toList(),
            )
        }
    }

    @Serializable
    @SerialName("limited")
    class Limited private constructor(
        val cardinality: ResultCardinality.KnownMinimum,
        @SerialName("dimensions")
        private val storedDimensions: List<ReplacementProofDimension>,
    ) : ReplacementOutboundEvidence {
        override val dimensions: List<ReplacementProofDimension>
            get() = Collections.unmodifiableList(storedDimensions)

        init {
            require(storedDimensions.isNotEmpty()) { "Limited replacement evidence needs a failed dimension" }
            require(storedDimensions.distinct().size == storedDimensions.size) {
                "Limited replacement evidence dimensions must be unique"
            }
        }

        companion object {
            fun of(
                knownMinimumCount: Int,
                dimensions: List<ReplacementProofDimension>,
            ): Limited = Limited(
                cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
                storedDimensions = dimensions.distinct().sortedBy(ReplacementProofDimension::ordinal),
            )
        }
    }
}

@Serializable
sealed interface ReplacementOutboundTarget {
    @Serializable
    @SerialName("source")
    data class Source(
        @DocField(description = "Exact compiler-resolved source declaration identity.")
        val symbol: SymbolIdentity,
    ) : ReplacementOutboundTarget

    @Serializable
    @SerialName("external")
    data class External(
        @DocField(description = "Compiler-provided fully-qualified external declaration name.")
        val fqName: String,
        @DocField(description = "Compiler symbol kind of the external declaration.")
        val kind: ReplacementCompilerSymbolKind,
        @DocField(description = "Canonical compiler signature that distinguishes external overloads.")
        val signature: ReplacementCompilerTargetSignature,
    ) : ReplacementOutboundTarget {
        init {
            require(fqName.isNotBlank()) { "External replacement target FQ name must not be blank" }
        }
    }
}

@Serializable
data class ReplacementTypeParameterSignature(
    @DocField(description = "Compiler-provided type parameter name.")
    val name: String,
    @DocField(description = "Canonical compiler type text for all declared upper bounds, in order.")
    val upperBounds: String,
    @DocField(description = "Compiler-provided variance of the type parameter.")
    val variance: ReplacementTypeVariance,
    @DocField(description = "Whether the type parameter is reified.")
    val reified: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Replacement type parameter name must not be blank" }
        require(upperBounds.isNotBlank()) { "Replacement type parameter upper bounds must not be blank" }
    }
}

@Serializable
data class ReplacementValueParameterSignature(
    @DocField(description = "Compiler-provided value parameter name.")
    val name: String,
    @DocField(description = "Canonical compiler type text for the value parameter.")
    val type: String,
    @DocField(description = "Whether the value parameter is vararg.")
    val vararg: Boolean,
    @DocField(description = "Whether the value parameter declares a default value.")
    val hasDefaultValue: Boolean,
    @DocField(description = "Whether the value parameter is noinline.")
    val noinline: Boolean,
    @DocField(description = "Whether the value parameter is crossinline.")
    val crossinline: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Replacement value parameter name must not be blank" }
        require(type.isNotBlank()) { "Replacement value parameter type must not be blank" }
    }
}

@Serializable
sealed interface ReplacementDeclarationSignature {
    val name: String
    val receiverType: String?
    val returnType: String
}

@Serializable
@SerialName("function")
class ReplacementFunctionSignature private constructor(
    override val name: String,
    override val receiverType: String?,
    @SerialName("contextReceiverTypes")
    private val storedContextReceiverTypes: List<String>,
    @SerialName("typeParameters")
    private val storedTypeParameters: List<ReplacementTypeParameterSignature>,
    @SerialName("valueParameters")
    private val storedValueParameters: List<ReplacementValueParameterSignature>,
    override val returnType: String,
    val visibility: ReplacementVisibility,
    val modality: ReplacementModality,
    val hasStableParameterNames: Boolean,
    val suspend: Boolean,
    val operator: Boolean,
    val inline: Boolean,
    val override: Boolean,
    val infix: Boolean,
    val static: Boolean,
    val tailrec: Boolean,
    val external: Boolean,
    val expect: Boolean,
    val actual: Boolean,
) : ReplacementDeclarationSignature {
    val contextReceiverTypes: List<String>
        get() = Collections.unmodifiableList(storedContextReceiverTypes)
    val typeParameters: List<ReplacementTypeParameterSignature>
        get() = Collections.unmodifiableList(storedTypeParameters)
    val valueParameters: List<ReplacementValueParameterSignature>
        get() = Collections.unmodifiableList(storedValueParameters)

    init {
        require(name.isNotBlank()) { "Replacement function name must not be blank" }
        require(receiverType == null || receiverType.isNotBlank()) {
            "Replacement function receiver type must be null or non-blank"
        }
        require(storedContextReceiverTypes.all(String::isNotBlank)) {
            "Replacement function context receiver types must not be blank"
        }
        require(returnType.isNotBlank()) { "Replacement function return type must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ReplacementFunctionSignature &&
        name == other.name &&
        receiverType == other.receiverType &&
        storedContextReceiverTypes == other.storedContextReceiverTypes &&
        storedTypeParameters == other.storedTypeParameters &&
        storedValueParameters == other.storedValueParameters &&
        returnType == other.returnType &&
        visibility == other.visibility &&
        modality == other.modality &&
        hasStableParameterNames == other.hasStableParameterNames &&
        suspend == other.suspend &&
        operator == other.operator &&
        inline == other.inline &&
        override == other.override &&
        infix == other.infix &&
        static == other.static &&
        tailrec == other.tailrec &&
        external == other.external &&
        expect == other.expect &&
        actual == other.actual

    override fun hashCode(): Int = listOf(
        name,
        receiverType,
        storedContextReceiverTypes,
        storedTypeParameters,
        storedValueParameters,
        returnType,
        visibility,
        modality,
        hasStableParameterNames,
        suspend,
        operator,
        inline,
        override,
        infix,
        static,
        tailrec,
        external,
        expect,
        actual,
    ).hashCode()

    override fun toString(): String =
        "ReplacementFunctionSignature(name=$name, receiverType=$receiverType, " +
            "contextReceiverTypes=$storedContextReceiverTypes, typeParameters=$storedTypeParameters, " +
            "valueParameters=$storedValueParameters, returnType=$returnType, visibility=$visibility, " +
            "modality=$modality, hasStableParameterNames=$hasStableParameterNames, suspend=$suspend, " +
            "operator=$operator, inline=$inline, override=$override, infix=$infix, static=$static, " +
            "tailrec=$tailrec, external=$external, expect=$expect, actual=$actual)"

    companion object {
        fun of(
            name: String,
            receiverType: String?,
            contextReceiverTypes: List<String>,
            typeParameters: List<ReplacementTypeParameterSignature>,
            valueParameters: List<ReplacementValueParameterSignature>,
            returnType: String,
            visibility: ReplacementVisibility,
            modality: ReplacementModality,
            hasStableParameterNames: Boolean,
            suspend: Boolean,
            operator: Boolean,
            inline: Boolean,
            override: Boolean,
            infix: Boolean,
            static: Boolean,
            tailrec: Boolean,
            external: Boolean,
            expect: Boolean,
            actual: Boolean,
        ): ReplacementFunctionSignature = ReplacementFunctionSignature(
            name = name,
            receiverType = receiverType,
            storedContextReceiverTypes = contextReceiverTypes.toList(),
            storedTypeParameters = typeParameters.toList(),
            storedValueParameters = valueParameters.toList(),
            returnType = returnType,
            visibility = visibility,
            modality = modality,
            hasStableParameterNames = hasStableParameterNames,
            suspend = suspend,
            operator = operator,
            inline = inline,
            override = override,
            infix = infix,
            static = static,
            tailrec = tailrec,
            external = external,
            expect = expect,
            actual = actual,
        )
    }
}

@Serializable
@SerialName("property")
class ReplacementPropertySignature private constructor(
    override val name: String,
    override val receiverType: String?,
    @SerialName("contextReceiverTypes")
    private val storedContextReceiverTypes: List<String>,
    @SerialName("typeParameters")
    private val storedTypeParameters: List<ReplacementTypeParameterSignature>,
    override val returnType: String,
    val visibility: ReplacementVisibility,
    val modality: ReplacementModality,
    val getterVisibility: ReplacementVisibility,
    val setterVisibility: ReplacementVisibility?,
    val hasGetter: Boolean,
    val hasSetter: Boolean,
    val hasBackingField: Boolean,
    val isVal: Boolean,
    val const: Boolean,
    val lateinit: Boolean,
    val delegated: Boolean,
    val override: Boolean,
    val static: Boolean,
    val external: Boolean,
    val expect: Boolean,
    val actual: Boolean,
) : ReplacementDeclarationSignature {
    val contextReceiverTypes: List<String>
        get() = Collections.unmodifiableList(storedContextReceiverTypes)
    val typeParameters: List<ReplacementTypeParameterSignature>
        get() = Collections.unmodifiableList(storedTypeParameters)

    init {
        require(name.isNotBlank()) { "Replacement property name must not be blank" }
        require(receiverType == null || receiverType.isNotBlank()) {
            "Replacement property receiver type must be null or non-blank"
        }
        require(storedContextReceiverTypes.all(String::isNotBlank)) {
            "Replacement property context receiver types must not be blank"
        }
        require(returnType.isNotBlank()) { "Replacement property return type must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ReplacementPropertySignature &&
        name == other.name &&
        receiverType == other.receiverType &&
        storedContextReceiverTypes == other.storedContextReceiverTypes &&
        storedTypeParameters == other.storedTypeParameters &&
        returnType == other.returnType &&
        visibility == other.visibility &&
        modality == other.modality &&
        getterVisibility == other.getterVisibility &&
        setterVisibility == other.setterVisibility &&
        hasGetter == other.hasGetter &&
        hasSetter == other.hasSetter &&
        hasBackingField == other.hasBackingField &&
        isVal == other.isVal &&
        const == other.const &&
        lateinit == other.lateinit &&
        delegated == other.delegated &&
        override == other.override &&
        static == other.static &&
        external == other.external &&
        expect == other.expect &&
        actual == other.actual

    override fun hashCode(): Int = listOf(
        name,
        receiverType,
        storedContextReceiverTypes,
        storedTypeParameters,
        returnType,
        visibility,
        modality,
        getterVisibility,
        setterVisibility,
        hasGetter,
        hasSetter,
        hasBackingField,
        isVal,
        const,
        lateinit,
        delegated,
        override,
        static,
        external,
        expect,
        actual,
    ).hashCode()

    override fun toString(): String =
        "ReplacementPropertySignature(name=$name, receiverType=$receiverType, " +
            "contextReceiverTypes=$storedContextReceiverTypes, typeParameters=$storedTypeParameters, " +
            "returnType=$returnType, visibility=$visibility, modality=$modality, " +
            "getterVisibility=$getterVisibility, setterVisibility=$setterVisibility, hasGetter=$hasGetter, " +
            "hasSetter=$hasSetter, hasBackingField=$hasBackingField, isVal=$isVal, const=$const, " +
            "lateinit=$lateinit, delegated=$delegated, override=$override, static=$static, external=$external, " +
            "expect=$expect, actual=$actual)"

    companion object {
        fun of(
            name: String,
            receiverType: String?,
            contextReceiverTypes: List<String>,
            typeParameters: List<ReplacementTypeParameterSignature>,
            returnType: String,
            visibility: ReplacementVisibility,
            modality: ReplacementModality,
            getterVisibility: ReplacementVisibility,
            setterVisibility: ReplacementVisibility?,
            hasGetter: Boolean,
            hasSetter: Boolean,
            hasBackingField: Boolean,
            isVal: Boolean,
            const: Boolean,
            lateinit: Boolean,
            delegated: Boolean,
            override: Boolean,
            static: Boolean,
            external: Boolean,
            expect: Boolean,
            actual: Boolean,
        ): ReplacementPropertySignature = ReplacementPropertySignature(
            name = name,
            receiverType = receiverType,
            storedContextReceiverTypes = contextReceiverTypes.toList(),
            storedTypeParameters = typeParameters.toList(),
            returnType = returnType,
            visibility = visibility,
            modality = modality,
            getterVisibility = getterVisibility,
            setterVisibility = setterVisibility,
            hasGetter = hasGetter,
            hasSetter = hasSetter,
            hasBackingField = hasBackingField,
            isVal = isVal,
            const = const,
            lateinit = lateinit,
            delegated = delegated,
            override = override,
            static = static,
            external = external,
            expect = expect,
            actual = actual,
        )
    }
}

@Serializable
@JvmInline
value class ReplacementDeclarationSha256(
    val value: String,
) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Replacement declaration SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }
}

@Serializable
data class ExactReplacementOutboundReference(
    @DocField(description = "Start offset relative to the proposed declaration.")
    val relativeStartOffset: Int,
    @DocField(description = "End offset relative to the proposed declaration.")
    val relativeEndOffset: Int,
    @DocField(description = "Exact reference source text in the proposed declaration.")
    val sourceText: String,
    @DocField(description = "Compiler-resolved outbound declaration identity.")
    val resolvedTarget: ReplacementOutboundTarget,
    @DocField(description = "Authority that established this occurrence and binding.")
    val provenance: ReplacementOccurrenceProvenance,
) {
    init {
        require(relativeStartOffset >= 0) { "Replacement reference start offset must be non-negative" }
        require(relativeEndOffset > relativeStartOffset) {
            "Replacement reference end offset must be after its start offset"
        }
        require(sourceText.isNotBlank()) { "Replacement reference source text must not be blank" }
        require(sourceText.length == relativeEndOffset - relativeStartOffset) {
            "Replacement reference source text must match its exact range"
        }
    }
}

@Serializable
class ExactReplacementProof private constructor(
    @DocField(description = "Exact compiler-resolved identity of the source declaration.")
    val target: SymbolIdentity,
    @DocField(description = "Semantic source generation required by this replacement proof.")
    val requiredGeneration: MutationSemanticGeneration,
    @DocField(description = "Exact full source range of the declaration to replace.")
    val sourceRange: Location,
    @SerialName("fileHashes")
    private val storedFileHashes: List<FileHash>,
    @DocField(description = "Typed compiler-observable signature of the existing declaration.")
    val oldSignature: ReplacementDeclarationSignature,
    @DocField(description = "Typed compiler-observable signature of the proposed declaration.")
    val proposedSignature: ReplacementDeclarationSignature,
    @DocField(description = "SHA-256 of the exact proposed declaration text.")
    val proposedDeclarationHash: ReplacementDeclarationSha256,
    @DocField(description = "Exact character length of the proposed declaration text.")
    val proposedDeclarationLength: Int,
    @DocField(description = "Operation-relative complete outbound-reference proof and exact occurrence cardinality.")
    val evidence: ReplacementOutboundEvidence.Complete,
    @SerialName("outboundReferences")
    private val storedOutboundReferences: List<ExactReplacementOutboundReference>,
) {
    val fileHashes: List<FileHash>
        get() = Collections.unmodifiableList(storedFileHashes)
    val outboundReferences: List<ExactReplacementOutboundReference>
        get() = Collections.unmodifiableList(storedOutboundReferences)

    init {
        require(target.kind == SymbolKind.FUNCTION || target.kind == SymbolKind.PROPERTY) {
            "Exact replacement proof supports only function and property targets"
        }
        require(sourceRange.filePath == target.declarationFile.value) {
            "Exact replacement source range must be in the target declaration file"
        }
        require(sourceRange.startOffset <= target.declarationStartOffset.value &&
            target.declarationStartOffset.value < sourceRange.endOffset) {
            "Exact replacement source range must contain the target declaration name"
        }
        require(storedFileHashes.size == 1 && storedFileHashes.single().filePath == sourceRange.filePath) {
            "Exact replacement proof requires one hash for the exact source file"
        }
        require(oldSignature == proposedSignature) {
            "Exact replacement signatures must be equal"
        }
        require(
            (target.kind == SymbolKind.FUNCTION && oldSignature is ReplacementFunctionSignature) ||
                (target.kind == SymbolKind.PROPERTY && oldSignature is ReplacementPropertySignature),
        ) { "Exact replacement signature kind must match the target kind" }
        require(proposedDeclarationLength > 0) { "Proposed replacement declaration must not be empty" }
        require(evidence.cardinality.totalCount == storedOutboundReferences.size) {
            "Exact replacement cardinality must match the outbound reference count"
        }
        require(storedOutboundReferences.all { reference ->
            reference.provenance == ReplacementOccurrenceProvenance.COMPILER &&
                reference.relativeEndOffset <= proposedDeclarationLength
        }) { "Every outbound replacement reference must have compiler provenance and an exact range" }
        require(storedOutboundReferences.map { reference ->
            reference.relativeStartOffset to reference.relativeEndOffset
        }.distinct().size == storedOutboundReferences.size) {
            "Outbound replacement references must have unique source ranges"
        }
    }

    companion object {
        fun of(
            target: SymbolIdentity,
            requiredGeneration: MutationSemanticGeneration,
            sourceRange: Location,
            fileHashes: List<FileHash>,
            oldSignature: ReplacementDeclarationSignature,
            proposedSignature: ReplacementDeclarationSignature,
            proposedDeclarationHash: ReplacementDeclarationSha256,
            proposedDeclarationLength: Int,
            evidence: ReplacementOutboundEvidence.Complete,
            outboundReferences: List<ExactReplacementOutboundReference>,
        ): ExactReplacementProof = ExactReplacementProof(
            target = target,
            requiredGeneration = requiredGeneration,
            sourceRange = sourceRange,
            storedFileHashes = fileHashes.toList(),
            oldSignature = oldSignature,
            proposedSignature = proposedSignature,
            proposedDeclarationHash = proposedDeclarationHash,
            proposedDeclarationLength = proposedDeclarationLength,
            evidence = evidence,
            storedOutboundReferences = outboundReferences.toList(),
        )
    }
}
