package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

enum class ImpactModelPrimitiveFailure {
    INVALID_IDENTIFIER,
    NOT_POSITIVE,
    UNSUPPORTED_FORMAT,
}

@JvmInline
@Serializable(with = ImpactModelIdentifierSerializer::class)
value class ImpactModelIdentifierDocument private constructor(val value: String) {
    companion object {
        const val PATTERN = "^[A-Za-z0-9._:/-]{1,128}$"
        private val admitted = Regex(PATTERN)

        fun parse(raw: String): Refinement<ImpactModelIdentifierDocument, ImpactModelPrimitiveFailure> =
            if (admitted.matches(raw)) Refinement.Refined(ImpactModelIdentifierDocument(raw))
            else Refinement.Rejected(ImpactModelPrimitiveFailure.INVALID_IDENTIFIER)
    }
}

internal object ImpactModelIdentifierSerializer :
    RefiningStringSerializer<ImpactModelIdentifierDocument>(
        "ImpactModelIdentifier",
        1,
        IMPACT_MODEL_IDENTIFIER_MAX_LENGTH,
        ImpactModelIdentifierDocument.PATTERN,
    ) {
    override fun raw(value: ImpactModelIdentifierDocument): String = value.value

    override fun refine(raw: String): Refinement<ImpactModelIdentifierDocument, *> =
        ImpactModelIdentifierDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactModelVersionSerializer::class)
value class ImpactModelVersionDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<ImpactModelVersionDocument, ImpactModelPrimitiveFailure> =
            if (raw > 0) Refinement.Refined(ImpactModelVersionDocument(raw))
            else Refinement.Rejected(ImpactModelPrimitiveFailure.NOT_POSITIVE)
    }
}

internal object ImpactModelVersionSerializer :
    RefiningIntSerializer<ImpactModelVersionDocument>("ImpactModelVersion", 1, Int.MAX_VALUE.toLong()) {
    override fun raw(value: ImpactModelVersionDocument): Int = value.value

    override fun refine(raw: Int): Refinement<ImpactModelVersionDocument, *> = ImpactModelVersionDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactEvidenceRevisionSerializer::class)
value class ImpactEvidenceRevisionDocument private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<ImpactEvidenceRevisionDocument, ImpactModelPrimitiveFailure> =
            if (raw > 0) Refinement.Refined(ImpactEvidenceRevisionDocument(raw))
            else Refinement.Rejected(ImpactModelPrimitiveFailure.NOT_POSITIVE)
    }
}

internal object ImpactEvidenceRevisionSerializer :
    RefiningLongSerializer<ImpactEvidenceRevisionDocument>("ImpactEvidenceRevision", 1) {
    override fun raw(value: ImpactEvidenceRevisionDocument): Long = value.value

    override fun refine(raw: Long): Refinement<ImpactEvidenceRevisionDocument, *> =
        ImpactEvidenceRevisionDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactModelFormatSerializer::class)
value class ImpactModelFormatDocument private constructor(val value: Int) {
    companion object {
        val Current = ImpactModelFormatDocument(1)

        fun parse(raw: Int): Refinement<ImpactModelFormatDocument, ImpactModelPrimitiveFailure> =
            if (raw == 1) Refinement.Refined(Current)
            else Refinement.Rejected(ImpactModelPrimitiveFailure.UNSUPPORTED_FORMAT)
    }
}

internal object ImpactModelFormatSerializer :
    RefiningIntSerializer<ImpactModelFormatDocument>("ImpactModelFormat", 1, 1) {
    override fun raw(value: ImpactModelFormatDocument): Int = value.value

    override fun refine(raw: Int): Refinement<ImpactModelFormatDocument, *> = ImpactModelFormatDocument.parse(raw)
}

private const val IMPACT_MODEL_IDENTIFIER_MAX_LENGTH = 128
