package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireFailure

fun interface CliRequestParser<Request : OperationRequest> {
    /**
     * Proof transition: `CliArguments -> CliRequestParsing<Request>`.
     *
     * Establishes the operation-specific typed request or rejects the complete argument sequence.
     * Expected failure is the closed [CliRequestParsing.Rejected] variant. Raw argument values may
     * be extracted only within this outer request boundary.
     */
    fun parse(arguments: CliArguments): CliRequestParsing<Request>
}

sealed interface CliRequestParsing<out Request : OperationRequest> {
    data class Parsed<Request : OperationRequest>(
        val request: Request,
    ) : CliRequestParsing<Request>

    data object Rejected : CliRequestParsing<Nothing>
}

fun interface CliOutcomeProjector<
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > {
    /**
     * Proof transition: `OperationOutcome<Result, Qualification, Rejection> ->
     * ProjectedCliOutcome`.
     *
     * Preserves the closed semantic outcome variant while producing canonical JSON. Raw result
     * extraction is permitted only within this outer presentation boundary.
     */
    fun project(
        outcome: OperationOutcome<Result, Qualification, Rejection>,
    ): ProjectedCliOutcome
}

/** A projection whose generic binding remains captured through request and outcome conversion. */
class TypedCliProjection<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    private val wireBinding: OperationWireBinding<Request, Result, Qualification, Rejection>,
    private val requestParser: CliRequestParser<Request>,
    private val outcomeProjector: CliOutcomeProjector<Result, Qualification, Rejection>,
) : CliProjection {
    override val operation: CanonicalOperation
        get() = wireBinding.operation

    override fun prepare(arguments: CliArguments): CliProjectionPreparation {
        val request = when (val parsed = requestParser.parse(arguments)) {
            is CliRequestParsing.Parsed -> parsed.request
            CliRequestParsing.Rejected -> return CliProjectionPreparation.Rejected(
                CliProjectionFailure.ArgumentsRejected(operation),
            )
        }
        val requestDocument = when (val encoded = wireBinding.encodeRequest(request)) {
            is WireEncoding.Encoded -> encoded.document
            is WireEncoding.Rejected -> return CliProjectionPreparation.Rejected(
                CliProjectionFailure.RequestEncodingFailed(operation, encoded.failure),
            )
        }
        return CliProjectionPreparation.Prepared(
            PreparedCliRequest(operation, requestDocument) { response ->
                when (val decoded = wireBinding.decodeOutcome(response)) {
                    is WireDecoding.Decoded -> CliProjectionCompletion.Completed(
                        outcomeProjector.project(decoded.value),
                    )
                    is WireDecoding.Rejected -> CliProjectionCompletion.Rejected(
                        CliProjectionFailure.ResponseDecodingFailed(operation, decoded.failure),
                    )
                }
            },
        )
    }
}

/** Type-erased routing surface whose implementation retains captured generic evidence. */
interface CliProjection {
    val operation: CanonicalOperation

    /**
     * Proof transition: `CliArguments -> CliProjectionPreparation`.
     *
     * Establishes a captured generated request document and outcome decoder for this exact
     * operation. [CliProjectionFailure] is the closed expected failure. Raw argument extraction
     * remains inside the captured [CliRequestParser].
     */
    fun prepare(arguments: CliArguments): CliProjectionPreparation
}

class PreparedCliRequest internal constructor(
    val operation: CanonicalOperation,
    val document: String,
    private val completion: (String) -> CliProjectionCompletion,
) {
    /**
     * Proof transition: `String -> CliProjectionCompletion`.
     *
     * Establishes the captured operation's generated outcome types and canonical JSON projection.
     * [CliProjectionFailure.ResponseDecodingFailed] is the closed expected failure. Raw response
     * text may be extracted only at this wire-decoding boundary.
     */
    fun complete(response: String): CliProjectionCompletion = completion(response)
}

sealed interface CliProjectionPreparation {
    data class Prepared(
        val request: PreparedCliRequest,
    ) : CliProjectionPreparation

    data class Rejected(
        val failure: CliProjectionFailure,
    ) : CliProjectionPreparation
}

sealed interface CliProjectionCompletion {
    data class Completed(
        val outcome: ProjectedCliOutcome,
    ) : CliProjectionCompletion

    data class Rejected(
        val failure: CliProjectionFailure,
    ) : CliProjectionCompletion
}

sealed interface CliProjectionFailure {
    data class ArgumentsRejected(
        val operation: CanonicalOperation,
    ) : CliProjectionFailure

    data class RequestEncodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : CliProjectionFailure

    data class ResponseDecodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : CliProjectionFailure
}

/** An immutable table proven to contain exactly one projection for every public operation. */
class CliProjectionTable private constructor(
    private val projections: Map<CanonicalOperation, CliProjection>,
) {
    /** Routes only after exact table completeness was established at construction. */
    fun prepare(invocation: CliInvocation): CliProjectionPreparation =
        projections.getValue(invocation.operation).prepare(invocation.arguments)

    companion object {
        /**
         * Proof transition: `Iterable<CliProjection> -> CliProjectionTableConstruction`.
         *
         * Establishes exactly one projection for each of the eleven canonical operations.
         * [CliProjectionTableFailure] is the closed expected failure. Weak iteration is permitted
         * only at runtime composition.
         */
        fun create(projections: Iterable<CliProjection>): CliProjectionTableConstruction {
            val materialized = projections.toList()
            val failures = buildSet {
                materialized.groupingBy(CliProjection::operation).eachCount()
                    .filterValues { count -> count > 1 }
                    .keys
                    .forEach { add(CliProjectionTableFailure.DuplicateProjection(it)) }
                val present = materialized.mapTo(mutableSetOf(), CliProjection::operation)
                CanonicalOperation.entries.filterNot(present::contains)
                    .forEach { add(CliProjectionTableFailure.MissingProjection(it)) }
            }
            return if (failures.isEmpty()) {
                CliProjectionTableConstruction.Created(
                    CliProjectionTable(materialized.associateBy(CliProjection::operation)),
                )
            } else {
                CliProjectionTableConstruction.Rejected(failures)
            }
        }
    }
}

sealed interface CliProjectionTableConstruction {
    data class Created(
        val table: CliProjectionTable,
    ) : CliProjectionTableConstruction

    data class Rejected(
        val failures: Set<CliProjectionTableFailure>,
    ) : CliProjectionTableConstruction
}

sealed interface CliProjectionTableFailure {
    data class MissingProjection(
        val operation: CanonicalOperation,
    ) : CliProjectionTableFailure

    data class DuplicateProjection(
        val operation: CanonicalOperation,
    ) : CliProjectionTableFailure
}
