package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactSiteAccountingShapeTest {
    private val f = ImpactFindingFixture()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `site outcomes encode every finite variant and required original path links`() {
        val links =
            listOf(
                ImpactFindingEvidenceReferenceDocument(f.count(0), f.row(1)),
                ImpactFindingEvidenceReferenceDocument(f.count(1), f.row(2)),
            )
        val exit =
            ImpactSiteExclusionDocument(
                links[1],
                ImpactRequestedBoundaryDocument.SourceDomain(
                    f.domain.copy(
                        directory = QueryDirectoryScopeDocument(f.text("selected"), QueryContainmentDocument.DIRECT)
                    )
                ),
                ImpactScopeExclusionDocument.OUTSIDE_DIRECTORY,
            )
        val outcomes =
            listOf(
                ImpactSiteOutcomeDocument.Reached(f.bounded(links), f.bounded(emptyList())),
                ImpactSiteOutcomeDocument.Excluded(f.bounded(listOf(exit))),
                ImpactSiteOutcomeDocument.RelationshipUnproven,
            )
        val expected =
            Json.parseToJsonElement(
                checkNotNull(javaClass.getResource("/impact-site-outcomes.expected.json")).readText()
            )
        assertEquals(
            expected,
            json.encodeToJsonElement(ListSerializer(ImpactSiteOutcomeDocument.serializer()), outcomes),
        )
    }

    @Test
    fun `missing native admission original identity and unknown site outcome fail decoding`() {
        val target =
            ImpactSiteAccountingDocument(
                f.count(0),
                f.destination,
                ImpactSiteOutcomeDocument.Reached(
                    f.bounded(listOf(ImpactFindingEvidenceReferenceDocument(f.count(0), f.row(1)))),
                    f.bounded(emptyList()),
                ),
                ImpactSiteAdmissionDocument(f.flowDomain.budget, f.count(1)),
            )
        val raw = json.encodeToString(ImpactSiteAccountingDocument.serializer(), target)
        for (malformed in
            listOf(
                raw.replace("\"REACHED\"", "\"ABSENT\""),
                raw.replace("\"requestedSiteOrdinal\":0,", ""),
                raw.replace("\"examinedWorkUnits\":1", "\"unproven\":1"),
                raw.replace("\"pathRowId\"", "\"notAnIdentity\""),
            )) {
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(ImpactSiteAccountingDocument.serializer(), malformed)
            }
        }
    }
}
