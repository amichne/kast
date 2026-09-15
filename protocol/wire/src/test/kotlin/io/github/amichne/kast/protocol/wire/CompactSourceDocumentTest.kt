package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CompactSourceDocumentTest {
    private val json = Json { encodeDefaults = true }
    private val fixture =
        CompactSourceReadDocument(
            snapshot =
                CompactSourceSnapshotDocument(
                    "/workspace",
                    7,
                    "state",
                    "Subject.kt",
                    "identity",
                    SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT,
                    1,
                ),
            region =
                CompactSourceRegionDocument(
                    SourceRegionKindWireDocument.FILE,
                    CompactSourceSelectionDocument(0, CompactSourceRangeDocument(0, 1)),
                ),
            text = CompactSourceTextDocument.NotRequested,
            selections = listOf(CompactSourceSelectionEntry("source-selector-v1|fixture")),
            entities = emptyList(),
        )

    @Test
    fun `compact table indices and canonical membership fail closed`() {
        assertInstanceOf(
            WireDecoding.Decoded::class.java,
            CanonicalSourceReadSerializers.result.decode(
                json.encodeToJsonElement(CompactSourceReadDocument.serializer(), fixture),
                WireValueRole.RESULT,
            ),
        )
        val invalid =
            listOf(
                fixture.copy(selections = emptyList()),
                fixture.copy(selections = fixture.selections + fixture.selections),
                fixture.copy(region = fixture.region.copy(selection = fixture.region.selection.copy(id = -1))),
                fixture.copy(region = fixture.region.copy(selection = fixture.region.selection.copy(id = 1))),
                fixture.copy(format = SourceReadFormatDocument.EXPANDED),
            )
        invalid.forEach { document ->
            assertInstanceOf(
                WireDecoding.Rejected::class.java,
                CanonicalSourceReadSerializers.result.decode(
                    json.encodeToJsonElement(CompactSourceReadDocument.serializer(), document),
                    WireValueRole.RESULT,
                ),
            )
        }
        assertEquals(
            "compact",
            json
                .encodeToJsonElement(SourceReadFormatDocument.serializer(), SourceReadFormatDocument.COMPACT)
                .toString()
                .trim('"'),
        )
    }
}
