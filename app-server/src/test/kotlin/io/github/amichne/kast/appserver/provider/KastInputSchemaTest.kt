package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastInputSchemaTest {
    @Test
    fun `nested unions admit only closed object leaves`() {
        val closed = ObjectSchema("object", false)
        val open = ObjectSchema("object", true)
        assertTrue(
            closedKastInputSchema(
                Json.encodeToJsonElement(NestedUnionSchema(listOf(UnionSchema(listOf(closed))))).jsonObject
            )
        )
        assertFalse(
            closedKastInputSchema(
                Json.encodeToJsonElement(NestedUnionSchema(listOf(UnionSchema(listOf(closed, open))))).jsonObject
            )
        )
    }

    @Test
    fun `closed union admits and open empty or nonobject branches retain input stage rejection`() = runTest {
        val closed = ObjectSchema("object", false)
        val cases =
            listOf(
                listOf(closed, closed) to true,
                listOf(closed, ObjectSchema("object", true)) to false,
                listOf(closed, ObjectSchema("string", false)) to false,
                emptyList<ObjectSchema>() to false,
            )
        for ((branches, accepted) in cases) {
            val events = mutableListOf<KastCatalogObservation>()
            val base = Json.decodeFromString<KastCapabilityBoundary>(installedKastCatalogFixture())
            val bootstrap = base.serverProjection.hostedBootstrap
            val tools =
                bootstrap.tools.map { tool ->
                    if (tool.name == "workspace_lifecycle")
                        tool.copy(inputSchema = Json.encodeToJsonElement(UnionSchema(branches)))
                    else tool
                }
            val document =
                base.copy(
                    serverProjection = base.serverProjection.copy(hostedBootstrap = bootstrap.copy(tools = tools))
                )
            val result =
                KastProviderQualifier.qualify(
                    KastProviderOptions(
                        catalogSource = KastCatalogSource { Refinement.Refined(Json.encodeToString(document)) },
                        catalogObserver = KastCatalogObserver(events::add),
                    )
                )
            if (accepted) {
                assertInstanceOf(KastProviderQualification.Qualified::class.java, result)
                assertEquals(listOf(KastCatalogObservation.Admitted), events)
            } else {
                assertEquals(KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE), result)
                assertEquals(
                    listOf(
                        KastCatalogObservation.Rejected(
                            KastCatalogStage.INPUT_SCHEMA,
                            KastQualificationFailure.SCHEMA_INCOMPATIBLE,
                        )
                    ),
                    events,
                )
            }
            assertEncodedObservation(events.single(), accepted)
        }
    }

    private fun assertEncodedObservation(event: KastCatalogObservation, accepted: Boolean) {
        val encoded = Json.encodeToJsonElement(event).jsonObject
        if (accepted) {
            assertEquals(setOf("type"), encoded.keys)
            assertEquals(JsonPrimitive("kast_catalog_admitted"), encoded["type"])
        } else {
            assertEquals(setOf("type", "stage", "failure"), encoded.keys)
            assertEquals(JsonPrimitive("kast_catalog_rejected"), encoded["type"])
            assertEquals(JsonPrimitive("INPUT_SCHEMA"), encoded["stage"])
            assertEquals(JsonPrimitive("SCHEMA_INCOMPATIBLE"), encoded["failure"])
        }
    }

    @Serializable private data class ObjectSchema(val type: String, val additionalProperties: Boolean)

    @Serializable private data class UnionSchema(val anyOf: List<ObjectSchema>)

    @Serializable private data class NestedUnionSchema(val anyOf: List<UnionSchema>)
}
