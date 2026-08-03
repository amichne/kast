package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
        @DocField(description = "Exact number of outbound reference occurrences in the proposed declaration.")
        @Serializable(with = ExactReplacementCardinalitySerializer::class)
        val cardinality: ResultCardinality.Exact,
        @DocField(description = "Complete closed set of replacement proof dimensions.")
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
        @DocField(description = "Known minimum outbound reference count when replacement proof is incomplete.")
        @Serializable(with = KnownMinimumReplacementCardinalitySerializer::class)
        val cardinality: ResultCardinality.KnownMinimum,
        @DocField(description = "Replacement proof dimensions established before the typed limitation.")
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

    object CompleteSerializer : KSerializer<Complete> {
        override val descriptor = Complete.serializer().descriptor

        override fun serialize(encoder: Encoder, value: Complete) {
            encoder.encodeSerializableValue(ReplacementOutboundEvidence.serializer(), value)
        }

        override fun deserialize(decoder: Decoder): Complete =
            when (val evidence = decoder.decodeSerializableValue(ReplacementOutboundEvidence.serializer())) {
                is Complete -> evidence
                is Limited -> throw SerializationException(
                    "Complete replacement evidence requires the complete variant",
                )
            }
    }
}

private object ExactReplacementCardinalitySerializer : KSerializer<ResultCardinality.Exact> {
    override val descriptor = ResultCardinality.Exact.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ResultCardinality.Exact) {
        encoder.encodeSerializableValue(ResultCardinality.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): ResultCardinality.Exact =
        when (val cardinality = decoder.decodeSerializableValue(ResultCardinality.serializer())) {
            is ResultCardinality.Exact -> cardinality
            is ResultCardinality.KnownMinimum -> throw SerializationException(
                "Complete replacement evidence requires exact cardinality",
            )
        }
}

private object KnownMinimumReplacementCardinalitySerializer : KSerializer<ResultCardinality.KnownMinimum> {
    override val descriptor = ResultCardinality.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ResultCardinality.KnownMinimum) {
        encoder.encodeSerializableValue(ResultCardinality.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): ResultCardinality.KnownMinimum =
        when (val cardinality = decoder.decodeSerializableValue(ResultCardinality.serializer())) {
            is ResultCardinality.Exact -> throw SerializationException(
                "Limited replacement evidence cannot claim exact cardinality",
            )
            is ResultCardinality.KnownMinimum -> cardinality
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
data class ExactReplacementOutboundReference(
    @DocField(description = "UTF-16 start offset relative to the full proposed edit.")
    val relativeStartOffset: Int,
    @DocField(description = "UTF-16 end offset relative to the full proposed edit.")
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
