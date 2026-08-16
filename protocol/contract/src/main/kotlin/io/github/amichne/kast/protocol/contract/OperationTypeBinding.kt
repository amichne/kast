package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlin.reflect.KClass

private const val MAX_SCHEMA_IDENTITY_LENGTH = 160
private val SCHEMA_IDENTITY_FORMAT =
    Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)+")

enum class SchemaIdentityFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

/** A permanent schema identity admitted for one public operation binding. */
@JvmInline
value class SchemaIdentity private constructor(
    val value: String,
) : Comparable<SchemaIdentity> {
    companion object {
        /**
         * Proof transition: `String -> Refinement<SchemaIdentity, SchemaIdentityFailure>`.
         *
         * Establishes a non-blank, bounded, lowercase, dot-separated schema identity.
         * [SchemaIdentityFailure] is the closed expected failure. Raw text may be extracted only
         * at the wire envelope boundary.
         */
        fun parse(raw: String): Refinement<SchemaIdentity, SchemaIdentityFailure> = when {
            raw.isBlank() -> Refinement.Rejected(SchemaIdentityFailure.BLANK)
            raw.length > MAX_SCHEMA_IDENTITY_LENGTH ->
                Refinement.Rejected(SchemaIdentityFailure.TOO_LONG)
            !SCHEMA_IDENTITY_FORMAT.matches(raw) ->
                Refinement.Rejected(SchemaIdentityFailure.INVALID_FORMAT)
            else -> Refinement.Refined(SchemaIdentity(raw))
        }
    }

    override fun compareTo(other: SchemaIdentity): Int = value.compareTo(other.value)
}

/** Marker for a typed public operation request. */
interface OperationRequest

/** Marker for a typed successful operation result. */
interface OperationResult

/** Marker for a typed qualification attached to incomplete successful evidence. */
interface OperationQualification

/** Marker for an operation-owned closed rejection reason. */
interface OperationRejection

/**
 * The complete nominal type and schema binding for one public operation.
 *
 * Generic bounds prevent primitives, `Any`, maps, and untyped payloads from becoming registry
 * metadata. Serializer bindings consume the same type parameters at the wire boundary.
 */
data class OperationTypeBinding<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val requestType: KClass<Request>,
    val resultType: KClass<Result>,
    val qualificationType: KClass<Qualification>,
    val rejectionType: KClass<Rejection>,
    val schema: SchemaIdentity,
)
