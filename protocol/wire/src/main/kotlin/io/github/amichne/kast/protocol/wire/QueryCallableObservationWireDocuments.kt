@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDispositionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableModuleKindDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableOriginDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallableObservationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QuerySourceLessCallableWireDocument(
    @SerialName("compiler_evidence") val compilerEvidence: CompilerSymbolEvidenceWireDocument,
    val kind: SymbolKindWireDocument,
    val origin: QuerySourceLessCallableOriginDocument,
    @SerialName("module_kind") val moduleKind: QuerySourceLessCallableModuleKindDocument,
    @SerialName("module_name")
    @ProtocolStringConstraint(minimumLength = 1, maximumLength = 512, pattern = "^[^\\x00-\\x1F\\x7F-\\x9F]+$")
    val moduleName: String,
)

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallableTargetWireDocument {
    @Serializable
    @SerialName("PARAMETER_INVOCATION")
    data class ParameterInvocation(val parameter: QueryCallbackParameterIdentityWireDocument) :
        QueryCallableTargetWireDocument

    @Serializable
    @SerialName("SOURCE_LESS")
    data class SourceLess(
        val callable: QuerySourceLessCallableWireDocument,
        val disposition: QuerySourceLessCallableDispositionDocument,
    ) : QueryCallableTargetWireDocument
}

@Serializable
internal data class QueryCallableObservationWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    @SerialName("lexical_owner") val lexicalOwner: QueryCallbackCallableWireDocument,
    val body: QueryCallbackBodyWireDocument,
    val target: QueryCallableTargetWireDocument,
)

internal fun QueryCallableObservationDocument.toWireDocument() =
    QueryCallableObservationWireDocument(
        RelationOccurrenceWireDocument(
            occurrence.candidateSelector.value,
            occurrence.file.value,
            occurrence.range.toWireDocument(),
        ),
        lexicalOwner.callbackWire(),
        body.callbackWire(),
        when (val value = target) {
            is QueryCallableTargetDocument.ParameterInvocation ->
                QueryCallableTargetWireDocument.ParameterInvocation(value.parameter.callbackWire())
            is QueryCallableTargetDocument.SourceLess ->
                QueryCallableTargetWireDocument.SourceLess(
                    QuerySourceLessCallableWireDocument(
                        value.callable.compilerEvidence.toWireDocument(),
                        value.callable.kind.toWireDocument(),
                        value.callable.origin,
                        value.callable.moduleKind,
                        value.callable.moduleName.value,
                    ),
                    value.disposition,
                )
        },
    )

internal fun QueryCallableObservationWireDocument.toContract():
    WireDocumentConversion<QueryCallableObservationDocument> =
    combineConverted(occurrence.toContract(), lexicalOwner.toContract(), body.toContract(), target.toContract()) {
            occurrence,
            lexicalOwner,
            body,
            target ->
            QueryCallableObservationDocument.create(occurrence, lexicalOwner, body, target).toWireDocumentConversion()
        }
        .flattenConverted()

private fun QueryCallableTargetWireDocument.toContract(): WireDocumentConversion<QueryCallableTargetDocument> =
    when (this) {
        is QueryCallableTargetWireDocument.ParameterInvocation ->
            parameter.toContract().mapConverted { QueryCallableTargetDocument.ParameterInvocation(it) }
        is QueryCallableTargetWireDocument.SourceLess ->
            combineConverted(
                    callable.compilerEvidence.toContract(),
                    ProtocolText.parse(callable.moduleName).toWireDocumentConversion(),
                ) { evidence, name ->
                    QuerySourceLessCallableDocument.create(
                            evidence,
                            callable.kind.toContract(),
                            callable.origin,
                            callable.moduleKind,
                            name,
                        )
                        .toWireDocumentConversion()
                }
                .flattenConverted()
                .mapConverted { QueryCallableTargetDocument.SourceLess(it, disposition) }
    }

@Serializable
internal data class QueryWalkCallableObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    @ProtocolIntegerConstraint(minimum = 0) val depth: Int,
    val observation: QueryCallableObservationWireDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
)

internal fun QueryWalkCallableObservationDocument.toWireDocument() =
    QueryWalkCallableObservationWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        depth.value,
        observation.toWireDocument(),
        requestedDomain,
        effectiveDomain,
        domainFingerprint,
    )

internal fun QueryWalkCallableObservationWireDocument.toContract():
    WireDocumentConversion<QueryWalkCallableObservationDocument> =
    combineConverted(
            ProtocolText.parse(subject.token).toWireDocumentConversion(),
            TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
            observation.toContract(),
        ) { token, depth, observation ->
            QueryWalkCallableObservationDocument(
                QueryReferenceDocument.ExactSymbol(token),
                depth,
                observation,
                requestedDomain,
                effectiveDomain,
                domainFingerprint,
            )
        }
        .flatMapConverted { value ->
            if (value.observation.admitsDomain(effectiveDomain)) WireDocumentConversion.Converted(value)
            else WireDocumentConversion.Rejected
        }
