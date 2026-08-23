package support.pr633

import kotlin.metadata.jvm.Metadata
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

internal data class RawKotlinSourceFile(val name: String, val content: String)

internal data class RawCompiledClassFile(val path: String, val bytes: ByteArray)

internal data class RawKotlinClassEvidence(
    val sourceFiles: List<String>, val metadata: List<RawKotlinMetadata>,
)

/**
 * Projection transition: `ClassReader -> RawKotlinClassEvidence`.
 *
 * Extracts only source-file and Kotlin-metadata annotation fields from an already parsed class.
 * The returned values remain weak boundary evidence and require semantic admission by the internal
 * visibility verifier.
 */
internal fun projectRawKotlinClassEvidence(reader: ClassReader): RawKotlinClassEvidence {
    val sourceFiles = mutableListOf<String>()
    val metadata = mutableListOf<RawKotlinMetadata>()
    reader.accept(
        KotlinClassEvidenceVisitor(sourceFiles, metadata),
        ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
    )
    return RawKotlinClassEvidence(sourceFiles, metadata)
}

private class KotlinClassEvidenceVisitor(
    private val sourceFiles: MutableList<String>,
    private val metadata: MutableList<RawKotlinMetadata>,
) : ClassVisitor(Opcodes.ASM9) {
    override fun visitSource(source: String?, debug: String?) {
        if (source != null) sourceFiles += source
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
        if (descriptor == "Lkotlin/Metadata;") {
            RawKotlinMetadata.visitor(metadata::add)
        } else {
            null
        }
}

internal data class RawKotlinMetadataField(
    val name: String,
    val value: Any,
)

internal sealed interface UnsupportedRawKotlinMetadataValue {
    data class EnumValue(val descriptor: String, val constantName: String) : UnsupportedRawKotlinMetadataValue
    data class AnnotationValue(val descriptor: String) : UnsupportedRawKotlinMetadataValue
    data object NestedArrayValue : UnsupportedRawKotlinMetadataValue
}

internal sealed interface RawKotlinMetadataFailure {
    data class UnknownField(val name: String) : RawKotlinMetadataFailure
    data class DuplicateField(val field: KotlinMetadataField) : RawKotlinMetadataFailure
    data class WrongFieldType(
        val field: KotlinMetadataField, val expected: KotlinMetadataFieldShape, val observed: String,
    ) : RawKotlinMetadataFailure
    data class WrongArrayElementType(
        val field: KotlinMetadataField, val index: Int, val observed: String,
    ) : RawKotlinMetadataFailure
    data class UnsupportedFieldValue(
        val field: KotlinMetadataField, val value: UnsupportedRawKotlinMetadataValue,
    ) : RawKotlinMetadataFailure
    data class UnsupportedArrayElementValue(
        val field: KotlinMetadataField, val index: Int, val value: UnsupportedRawKotlinMetadataValue,
    ) : RawKotlinMetadataFailure
}

internal enum class KotlinMetadataField(
    val encodedName: String, val shape: KotlinMetadataFieldShape,
) {
    KIND("k", KotlinMetadataFieldShape.INTEGER),
    METADATA_VERSION("mv", KotlinMetadataFieldShape.INTEGER_ARRAY),
    DATA1("d1", KotlinMetadataFieldShape.STRING_ARRAY),
    DATA2("d2", KotlinMetadataFieldShape.STRING_ARRAY),
    EXTRA_STRING("xs", KotlinMetadataFieldShape.STRING),
    PACKAGE_NAME("pn", KotlinMetadataFieldShape.STRING),
    EXTRA_INT("xi", KotlinMetadataFieldShape.INTEGER),
}

internal enum class KotlinMetadataFieldShape {
    INTEGER,
    INTEGER_ARRAY,
    STRING,
    STRING_ARRAY,
}

internal sealed interface RawKotlinMetadataAdmission {
    data class Proven(val value: AdmittedKotlinMetadata) : RawKotlinMetadataAdmission
    data class Rejected(val failure: RawKotlinMetadataFailure) : RawKotlinMetadataAdmission
}

internal class AdmittedKotlinMetadata private constructor(val annotation: Metadata) {
    companion object {
        /**
         * Proof transition: `List<RawKotlinMetadataField> -> RawKotlinMetadataAdmission`.
         *
         * Establishes that every observed annotation field is supported, unique, and has its exact
         * scalar or array-element shape. Expected failure is `RawKotlinMetadataFailure`; raw ASM
         * values enter only through `RawKotlinMetadata.admitAnnotation`.
         */
        fun refine(fields: List<RawKotlinMetadataField>): RawKotlinMetadataAdmission {
            val values = linkedMapOf<KotlinMetadataField, Any>()
            for (raw in fields) {
                val field = KotlinMetadataField.entries.find { it.encodedName == raw.name }
                    ?: return RawKotlinMetadataAdmission.Rejected(
                        RawKotlinMetadataFailure.UnknownField(raw.name),
                    )
                if (values.put(field, raw.value) != null) {
                    return RawKotlinMetadataAdmission.Rejected(
                        RawKotlinMetadataFailure.DuplicateField(field),
                    )
                }
            }
            val kind = when (val result = values.admitInt(KotlinMetadataField.KIND)) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            val metadataVersion = when (
                val result = values.admitIntArray(KotlinMetadataField.METADATA_VERSION)
            ) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            val data1 = when (val result = values.admitStringArray(KotlinMetadataField.DATA1)) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            val data2 = when (val result = values.admitStringArray(KotlinMetadataField.DATA2)) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            val extraString = when (
                val result = values.admitString(KotlinMetadataField.EXTRA_STRING)
            ) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            val packageName = when (
                val result = values.admitString(KotlinMetadataField.PACKAGE_NAME)
            ) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            val extraInt = when (val result = values.admitInt(KotlinMetadataField.EXTRA_INT)) {
                is MetadataFieldAdmission.Proven -> result.value
                is MetadataFieldAdmission.Rejected ->
                    return RawKotlinMetadataAdmission.Rejected(result.failure)
            }
            return RawKotlinMetadataAdmission.Proven(
                AdmittedKotlinMetadata(
                    Metadata(
                        kind = kind.atMetadataBoundary(),
                        metadataVersion = metadataVersion.atMetadataBoundary(),
                        data1 = data1.atMetadataBoundary(),
                        data2 = data2.atMetadataBoundary(),
                        extraString = extraString.atMetadataBoundary(),
                        packageName = packageName.atMetadataBoundary(),
                        extraInt = extraInt.atMetadataBoundary(),
                    ),
                ),
            )
        }
    }
}

private sealed interface OptionalMetadataField<out T> {
    data object Absent : OptionalMetadataField<Nothing>
    data class Present<T>(val value: T) : OptionalMetadataField<T>
}

private sealed interface MetadataFieldAdmission<out T> {
    data class Proven<T>(val value: OptionalMetadataField<T>) : MetadataFieldAdmission<T>
    data class Rejected(val failure: RawKotlinMetadataFailure) : MetadataFieldAdmission<Nothing>
}

/**
 * Proof transition: `Map<KotlinMetadataField, Any> -> MetadataFieldAdmission<Int>`.
 *
 * Establishes exact integer shape when the raw annotation field is present. Expected failure is
 * `RawKotlinMetadataFailure.WrongFieldType`; raw ASM values are permitted only in this admission.
 */
private fun Map<KotlinMetadataField, Any>.admitInt(
    field: KotlinMetadataField,
): MetadataFieldAdmission<Int> = when (val raw = this[field]) {
    null -> MetadataFieldAdmission.Proven(OptionalMetadataField.Absent)
    is Int -> MetadataFieldAdmission.Proven(OptionalMetadataField.Present(raw))
    else -> MetadataFieldAdmission.Rejected(raw.wrongType(field))
}

/**
 * Proof transition: `Map<KotlinMetadataField, Any> -> MetadataFieldAdmission<IntArray>`.
 *
 * Establishes exact JVM integer-array shape when present. Expected failure is the closed wrong
 * field-shape variant; raw ASM values are permitted only in this admission.
 */
private fun Map<KotlinMetadataField, Any>.admitIntArray(
    field: KotlinMetadataField,
): MetadataFieldAdmission<IntArray> = when (val raw = this[field]) {
    null -> MetadataFieldAdmission.Proven(OptionalMetadataField.Absent)
    is IntArray -> MetadataFieldAdmission.Proven(OptionalMetadataField.Present(raw.copyOf()))
    else -> MetadataFieldAdmission.Rejected(raw.wrongType(field))
}

/**
 * Proof transition: `Map<KotlinMetadataField, Any> -> MetadataFieldAdmission<String>`.
 *
 * Establishes exact string shape when present. Expected failure is the closed wrong field-shape
 * variant; raw ASM values are permitted only in this admission.
 */
private fun Map<KotlinMetadataField, Any>.admitString(
    field: KotlinMetadataField,
): MetadataFieldAdmission<String> = when (val raw = this[field]) {
    null -> MetadataFieldAdmission.Proven(OptionalMetadataField.Absent)
    is String -> MetadataFieldAdmission.Proven(OptionalMetadataField.Present(raw))
    else -> MetadataFieldAdmission.Rejected(raw.wrongType(field))
}

/**
 * Proof transition: `Map<KotlinMetadataField, Any> -> MetadataFieldAdmission<Array<String>>`.
 *
 * Establishes exact list and element shapes when present. Expected failure is the closed wrong
 * field or element-shape variant; raw ASM values are permitted only in this admission.
 */
private fun Map<KotlinMetadataField, Any>.admitStringArray(
    field: KotlinMetadataField,
): MetadataFieldAdmission<Array<String>> {
    val raw = this[field] ?: return MetadataFieldAdmission.Proven(OptionalMetadataField.Absent)
    if (raw !is List<*>) return MetadataFieldAdmission.Rejected(raw.wrongType(field))
    val values = mutableListOf<String>()
    raw.forEachIndexed { index, element ->
        if (element is UnsupportedRawKotlinMetadataValue) {
            return MetadataFieldAdmission.Rejected(
                RawKotlinMetadataFailure.UnsupportedArrayElementValue(field, index, element),
            )
        }
        if (element !is String) {
            return MetadataFieldAdmission.Rejected(
                RawKotlinMetadataFailure.WrongArrayElementType(
                    field,
                    index,
                    element?.javaClass?.name ?: "null",
                ),
            )
        }
        values += element
    }
    return MetadataFieldAdmission.Proven(OptionalMetadataField.Present(values.toTypedArray()))
}

/**
 * Failure projection: `(Any, KotlinMetadataField) -> RawKotlinMetadataFailure`.
 *
 * Captures the supported shape and observed runtime type after field admission rejects.
 */
private fun Any.wrongType(field: KotlinMetadataField): RawKotlinMetadataFailure = when (this) {
    is UnsupportedRawKotlinMetadataValue ->
        RawKotlinMetadataFailure.UnsupportedFieldValue(field, this)
    else -> RawKotlinMetadataFailure.WrongFieldType(field, field.shape, javaClass.name)
}

/**
 * Boundary projection: `RawKotlinMetadataFailure -> String`.
 *
 * Renders the closed raw field-shape rejection set only for the owning Gradle failure boundary.
 */
internal fun RawKotlinMetadataFailure.render(): String = when (this) {
    is RawKotlinMetadataFailure.UnknownField -> "unknown field '$name'"
    is RawKotlinMetadataFailure.DuplicateField -> "duplicate field '${field.encodedName}'"
    is RawKotlinMetadataFailure.WrongFieldType ->
        "field '${field.encodedName}' expected $expected, observed $observed"
    is RawKotlinMetadataFailure.WrongArrayElementType ->
        "field '${field.encodedName}' element $index is $observed, not String"
    is RawKotlinMetadataFailure.UnsupportedFieldValue ->
        "field '${field.encodedName}' has unsupported ${value.render()}"
    is RawKotlinMetadataFailure.UnsupportedArrayElementValue ->
        "field '${field.encodedName}' element $index has unsupported ${value.render()}"
}

/** Boundary projection from an unsupported raw callback value to its diagnostic text. */
private fun UnsupportedRawKotlinMetadataValue.render(): String = when (this) {
    is UnsupportedRawKotlinMetadataValue.EnumValue ->
        "enum value $descriptor#$constantName"
    is UnsupportedRawKotlinMetadataValue.AnnotationValue -> "annotation value $descriptor"
    UnsupportedRawKotlinMetadataValue.NestedArrayValue -> "nested array value"
}

/**
 * Boundary projection: `OptionalMetadataField<T> -> T?`.
 *
 * Converts proven annotation absence to the nullable default protocol required by `Metadata` only
 * at its constructor boundary; no downstream domain state retains the nullable representation.
 */
private fun <T> OptionalMetadataField<T>.atMetadataBoundary(): T? = when (this) {
    OptionalMetadataField.Absent -> null
    is OptionalMetadataField.Present -> value
}

internal class RawKotlinMetadata private constructor(
    private val fields: List<RawKotlinMetadataField>,
) {
    /**
     * Proof transition: `RawKotlinMetadata -> RawKotlinMetadataAdmission`.
     *
     * Establishes exact supported annotation field shapes before constructing the official
     * `Metadata` boundary value. Expected failure is `RawKotlinMetadataFailure`; semantic kind,
     * identity, and visibility remain unproven until the strict metadata parser consumes the result.
     */
    fun admitAnnotation(): RawKotlinMetadataAdmission = AdmittedKotlinMetadata.refine(fields)

    companion object {
        /**
         * Boundary projection: `ASM annotation callbacks -> RawKotlinMetadata`.
         *
         * Captures one annotation without asserting that it is complete or semantically valid.
         * The supplied consumer receives the raw aggregate only after `visitEnd`.
         */
        fun visitor(consume: (RawKotlinMetadata) -> Unit): AnnotationVisitor {
            val fields = mutableListOf<RawKotlinMetadataField>()
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any) {
                    fields += RawKotlinMetadataField(name, value)
                }

                override fun visitEnum(name: String, descriptor: String, value: String) {
                    fields += RawKotlinMetadataField(
                        name,
                        UnsupportedRawKotlinMetadataValue.EnumValue(descriptor, value),
                    )
                }

                override fun visitAnnotation(name: String, descriptor: String): AnnotationVisitor {
                    fields += RawKotlinMetadataField(
                        name,
                        UnsupportedRawKotlinMetadataValue.AnnotationValue(descriptor),
                    )
                    return object : AnnotationVisitor(Opcodes.ASM9) {}
                }

                override fun visitArray(name: String): AnnotationVisitor {
                    val values = mutableListOf<Any>()
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(ignored: String?, value: Any) {
                            values += value
                        }

                        override fun visitEnum(
                            ignored: String?,
                            descriptor: String,
                            value: String,
                        ) {
                            values += UnsupportedRawKotlinMetadataValue.EnumValue(descriptor, value)
                        }

                        override fun visitAnnotation(
                            ignored: String?,
                            descriptor: String,
                        ): AnnotationVisitor {
                            values += UnsupportedRawKotlinMetadataValue.AnnotationValue(descriptor)
                            return object : AnnotationVisitor(Opcodes.ASM9) {}
                        }

                        override fun visitArray(ignored: String?): AnnotationVisitor {
                            values += UnsupportedRawKotlinMetadataValue.NestedArrayValue
                            return object : AnnotationVisitor(Opcodes.ASM9) {}
                        }

                        override fun visitEnd() {
                            fields += RawKotlinMetadataField(name, values.toList())
                        }
                    }
                }

                override fun visitEnd() {
                    consume(RawKotlinMetadata(fields.toList()))
                }
            }
        }
    }
}
