@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallbackParentDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryComponentInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadOrderDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInputEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryCorrelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryFamilyDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMappedEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedPreservedKeyOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class CallbackSchemaFixture(schema: JsonObject = installedServerOutputSchema(CanonicalOperation.QUERY_RUN)) {
    private val definitions = schema.getValue("\$defs").jsonObject

    fun definition(name: String): JsonObject = definitions.getValue(name).jsonObject

    fun validator(name: String) =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                Json.encodeToString(DefinitionSchema.serializer(), DefinitionSchema("#/\$defs/$name", definitions))
            )
}

@Serializable
private data class DefinitionSchema(
    @SerialName("\$ref") val reference: String,
    @SerialName("\$defs") val definitions: Map<String, JsonElement>,
)

/** Independent of installed factoring: exact invocation and callback-capture descriptors end recursive expansion. */
internal fun independentImpactSchema(serializer: KSerializer<*>): JsonObject =
    generatedOutputSchema(
        serializer,
        CanonicalSchemaReferences.from(
            mapOf(
                "impactInvocationReference" to ImpactInvocationReferenceDocument.serializer(),
                "impactCaptureInvocation" to ImpactCaptureInvocationDocument.serializer(),
            )
        ),
    )

/** Validation resolves independently selected descriptors without expanding repeated proof trees. */
internal fun independentImpactSchemaWithDefinitions(serializer: KSerializer<*>): JsonObject {
    val graph = independentFactoredImpactSchema(serializer)
    return Json.encodeToJsonElement(
            DefinitionSchema.serializer(),
            DefinitionSchema(
                reference = "#/\$defs/independentRoot",
                definitions = mapOf("independentRoot" to graph.root) + graph.definitions,
            ),
        )
        .jsonObject
}

/** Independent serializer selection keeps each shared proof definition available for structural comparison. */
internal fun independentFactoredImpactSchema(serializer: KSerializer<*>): IndependentSchemaGraph =
    IndependentImpactSchemas().graph(serializer)

internal class IndependentImpactSchemas {
    private val shared =
        mapOf(
            "impactInvocationReference" to ImpactInvocationReferenceDocument.serializer(),
            "impactCaptureInvocation" to ImpactCaptureInvocationDocument.serializer(),
            "impactValueSiteReference" to ImpactValueSiteReferenceDocument.serializer(),
            "impactExecutionOwner" to ImpactExecutionOwnerDocument.serializer(),
            "impactReviewedValueBinding" to ImpactReviewedValueBindingDocument.serializer(),
            "impactReviewedCallValueBinding" to ImpactReviewedCallValueBindingDocument.serializer(),
            "impactReviewedTupleOrigin" to ImpactReviewedTupleOriginDocument.serializer(),
            "impactReviewedMapEntryCorrelation" to ImpactReviewedMapEntryCorrelationDocument.serializer(),
            "impactReviewedMapEntryOrigin" to ImpactReviewedMapEntryOriginDocument.serializer(),
            "impactReviewedMapEntryFamily" to ImpactReviewedMapEntryFamilyDocument.serializer(),
            "impactReviewedInputEntryOrigin" to ImpactReviewedInputEntryOriginDocument.serializer(),
            "impactReviewedPreservedKeyOrigin" to ImpactReviewedPreservedKeyOriginDocument.serializer(),
            "impactReviewedEntryReadOrder" to ImpactReviewedEntryReadOrderDocument.serializer(),
            "impactReviewedEntryComponentInvocation" to ImpactReviewedEntryComponentInvocationDocument.serializer(),
            "impactReviewedMappedEntryOrigin" to ImpactReviewedMappedEntryOriginDocument.serializer(),
            "impactReviewedCallbackParent" to ImpactReviewedCallbackParentDocument.serializer(),
            "impactReviewedDistinctStringKeys" to ImpactReviewedDistinctStringKeysDocument.serializer(),
        )
    private val references = CanonicalSchemaReferences.from(shared)
    private val definitions = shared.mapValues { (_, definition) -> generatedOutputSchema(definition, references) }

    fun graph(serializer: KSerializer<*>): IndependentSchemaGraph =
        IndependentSchemaGraph(generatedOutputSchema(serializer, references), definitions)
}
