package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class ImpactValueModelPrimitiveFailure {
    INVALID_DIGEST,
    INVALID_ARTIFACT_VERSION,
    INVALID_MODULE_NAME,
    INVALID_SELECTED_ELEMENT,
    INVALID_INSERTED_ELEMENT,
}

@JvmInline
@Serializable(with = ImpactLibraryDigestSerializer::class)
value class ImpactLibraryDigestDocument private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ImpactLibraryDigestDocument, ImpactValueModelPrimitiveFailure> =
            if (raw.length == DIGEST_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(ImpactLibraryDigestDocument(raw))
            else Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_DIGEST)
    }
}

internal object ImpactLibraryDigestSerializer :
    RefiningStringSerializer<ImpactLibraryDigestDocument>(
        "ImpactLibraryDigest",
        DIGEST_LENGTH,
        DIGEST_LENGTH,
        "^[0-9a-f]{64}$",
    ) {
    override fun raw(value: ImpactLibraryDigestDocument): String = value.value

    override fun refine(raw: String): Refinement<ImpactLibraryDigestDocument, *> =
        ImpactLibraryDigestDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactLibraryVersionSerializer::class)
value class ImpactLibraryVersionDocument private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ImpactLibraryVersionDocument, ImpactValueModelPrimitiveFailure> =
            if (raw.isLibraryLabel()) Refinement.Refined(ImpactLibraryVersionDocument(raw))
            else Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_ARTIFACT_VERSION)
    }
}

internal object ImpactLibraryVersionSerializer :
    RefiningStringSerializer<ImpactLibraryVersionDocument>("ImpactLibraryVersion", 1, LABEL_LENGTH) {
    override fun raw(value: ImpactLibraryVersionDocument): String = value.value

    override fun refine(raw: String): Refinement<ImpactLibraryVersionDocument, *> =
        ImpactLibraryVersionDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactLibraryModuleSerializer::class)
value class ImpactLibraryModuleDocument private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ImpactLibraryModuleDocument, ImpactValueModelPrimitiveFailure> =
            if (raw.isLibraryLabel()) Refinement.Refined(ImpactLibraryModuleDocument(raw))
            else Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_MODULE_NAME)
    }
}

internal object ImpactLibraryModuleSerializer :
    RefiningStringSerializer<ImpactLibraryModuleDocument>("ImpactLibraryModule", 1, LABEL_LENGTH) {
    override fun raw(value: ImpactLibraryModuleDocument): String = value.value

    override fun refine(raw: String): Refinement<ImpactLibraryModuleDocument, *> =
        ImpactLibraryModuleDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactSelectedElementSerializer::class)
value class ImpactSelectedElementDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<ImpactSelectedElementDocument, ImpactValueModelPrimitiveFailure> =
            if (raw == 0) Refinement.Refined(ImpactSelectedElementDocument(raw))
            else Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_SELECTED_ELEMENT)
    }
}

internal object ImpactSelectedElementSerializer :
    RefiningIntSerializer<ImpactSelectedElementDocument>("ImpactSelectedElement", 0, 0) {
    override fun raw(value: ImpactSelectedElementDocument): Int = value.value

    override fun refine(raw: Int): Refinement<ImpactSelectedElementDocument, *> =
        ImpactSelectedElementDocument.parse(raw)
}

@JvmInline
@Serializable(with = ImpactInsertedElementSerializer::class)
value class ImpactInsertedElementDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<ImpactInsertedElementDocument, ImpactValueModelPrimitiveFailure> =
            if (raw == 0) Refinement.Refined(ImpactInsertedElementDocument(raw))
            else Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_INSERTED_ELEMENT)
    }
}

internal object ImpactInsertedElementSerializer :
    RefiningIntSerializer<ImpactInsertedElementDocument>("ImpactInsertedElement", 0, 0) {
    override fun raw(value: ImpactInsertedElementDocument): Int = value.value

    override fun refine(raw: Int): Refinement<ImpactInsertedElementDocument, *> =
        ImpactInsertedElementDocument.parse(raw)
}

private const val DIGEST_LENGTH = 64
private const val LABEL_LENGTH = 256

private fun String.isLibraryLabel(): Boolean = isNotBlank() && length <= LABEL_LENGTH && none { it.isISOControl() }
