package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class KastCatalogAdmissionTest {
    @kotlinx.serialization.Serializable private data object EmptyCatalog

    @Test
    fun `catalog rejects missing duplicate foreign and incompatible identities`() = runTest {
        val base = Json.decodeFromString<KastCapabilityBoundary>(installedKastCatalogFixture())
        val projection = base.serverProjection
        val bootstrap = projection.hostedBootstrap
        val tools = bootstrap.tools
        val cases =
            listOf(
                base.copy(schemaVersion = 2),
                base.copy(serverProjection = projection.copy(namespace = "foreign")),
                base.copy(serverProjection = projection.copy(schemaVersion = 14)),
                base.copy(serverProjection = projection.copy(hostedBootstrap = bootstrap.copy(schemaVersion = 2))),
                base.copy(
                    serverProjection = projection.copy(hostedBootstrap = bootstrap.copy(tools = tools.dropLast(1)))
                ),
                base.copy(
                    serverProjection = projection.copy(hostedBootstrap = bootstrap.copy(tools = tools + tools.first()))
                ),
                base.copy(
                    serverProjection =
                        projection.copy(
                            hostedBootstrap =
                                bootstrap.copy(tools = tools.dropLast(1) + tools.last().copy(name = "foreign"))
                        )
                ),
                base.copy(
                    serverProjection =
                        projection.copy(
                            hostedBootstrap =
                                bootstrap.copy(
                                    tools =
                                        tools.dropLast(1) + tools.last().copy(approvalPolicy = KastApprovalPolicy.NONE)
                                )
                        )
                ),
            )
        for (document in cases) {
            assertEquals(
                KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
                KastProviderQualifier.qualify(
                    KastProviderOptions(KastCatalogSource { Refinement.Refined(Json.encodeToString(document)) })
                ),
            )
        }
        assertInstanceOf(
            KastProviderQualification.Qualified::class.java,
            KastProviderQualifier.qualify(
                KastProviderOptions(KastCatalogSource { Refinement.Refined(Json.encodeToString(base)) })
            ),
        )
    }

    @Test
    fun `malformed catalog rejects without registration`() = runTest {
        for (document in
            listOf(
                "{",
                Json.encodeToString(emptyList<String>()),
                Json.encodeToString(EmptyCatalog),
                installedKastCatalogFixture().replace("\"none\"", "\"unknown\""),
            )) {
            assertEquals(
                KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INVALID),
                KastProviderQualifier.qualify(KastProviderOptions(KastCatalogSource { Refinement.Refined(document) })),
            )
        }
    }
}
