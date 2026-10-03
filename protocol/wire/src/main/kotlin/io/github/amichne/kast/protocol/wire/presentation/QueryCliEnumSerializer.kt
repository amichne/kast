@file:OptIn(
    kotlinx.serialization.InternalSerializationApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The descriptor and emitted spelling retain the same closed domain enumeration. */
internal abstract class QueryCliEnumSerializer<Value : Enum<Value>>(
    name: String,
    private val values: List<Value>,
) : KSerializer<Value> {
    override val descriptor =
        buildSerialDescriptor(name, SerialKind.ENUM) {
            values.forEach {
                element(
                    it.cliName(),
                    kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
                        "$name.${it.name}",
                        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
                    ),
                )
            }
        }

    override fun serialize(encoder: Encoder, value: Value) = encoder.encodeEnum(descriptor, values.indexOf(value))

    override fun deserialize(decoder: Decoder): Value = values[decoder.decodeEnum(descriptor)]
}

internal object QueryReferenceRejectionCliSerializer :
    QueryCliEnumSerializer<QueryReferenceRejectionReason>(
        "QueryReferenceRejectionCliReason",
        QueryReferenceRejectionReason.entries,
    )

internal object QuerySourceRejectionCliSerializer :
    QueryCliEnumSerializer<QuerySourceRejectionReason>(
        "QuerySourceRejectionCliReason",
        QuerySourceRejectionReason.entries,
    )

internal object QueryDeclarationKindCliSerializer :
    QueryCliEnumSerializer<QueryDeclarationKindDocument>(
        "QueryDeclarationKindCli",
        QueryDeclarationKindDocument.entries,
    )

internal object QueryExecutionRejectionCliSerializer :
    QueryCliEnumSerializer<QueryExecutionRejectionDocument>(
        "QueryExecutionRejectionCliReason",
        QueryExecutionRejectionDocument.entries,
    )

internal object QueryLimitationCliSerializer :
    QueryCliEnumSerializer<QueryLimitationDocument>(
        "QueryLimitationCli",
        QueryLimitationDocument.entries,
    )

internal object QueryTerminalReasonCliSerializer :
    QueryCliEnumSerializer<QueryTerminalReasonDocument>(
        "QueryTerminalReasonCli",
        QueryTerminalReasonDocument.entries,
    )

internal object QueryExactFailureCliSerializer :
    QueryCliEnumSerializer<QueryExactFailureDocument>("QueryExactFailureDocumentCli", QueryExactFailureDocument.entries)

internal object QueryPredicateFailureCliSerializer :
    QueryCliEnumSerializer<QueryPredicateFailureDocument>(
        "QueryPredicateFailureDocumentCli",
        QueryPredicateFailureDocument.entries,
    )

internal object QuerySourceFailureCliSerializer :
    QueryCliEnumSerializer<QuerySourceFailureDocument>(
        "QuerySourceFailureDocumentCli",
        QuerySourceFailureDocument.entries,
    )

internal object QueryRelationFailureCliSerializer :
    QueryCliEnumSerializer<QueryRelationFailureDocument>(
        "QueryRelationFailureDocumentCli",
        QueryRelationFailureDocument.entries,
    )

internal object RelationKindCliSerializer :
    QueryCliEnumSerializer<RelationKindDocument>("RelationKindDocumentCli", RelationKindDocument.entries)
