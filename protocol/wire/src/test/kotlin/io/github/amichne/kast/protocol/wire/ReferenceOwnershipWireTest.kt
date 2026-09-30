package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationOwnershipUnavailableCauseDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReferenceOwnershipWireTest {
    @Test
    fun `file and unavailable ownership encode closed evidence without a fabricated declaration`() {
        val cases =
            listOf(
                RelationReferenceOwnershipWireDocument.FileScoped(RelationReferenceContextDocument.ALIASED_IMPORT) to
                    fixture("file-scoped.expected.json"),
                RelationReferenceOwnershipWireDocument.Unavailable(
                    RelationOwnershipUnavailableCauseDocument.UNSUPPORTED_DECLARATION
                ) to fixture("unavailable.expected.json"),
            )
        for ((value, expected) in cases) {
            assertEquals(
                wireJson.parseToJsonElement(expected),
                wireJson.encodeToJsonElement(RelationReferenceOwnershipWireDocument.serializer(), value),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                RelationReferenceOwnershipWireDocument.serializer(),
                fixture("unknown-context.invalid.json"),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                RelationReferenceOwnershipWireDocument.serializer(),
                fixture("missing-type.invalid.json"),
            )
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/reference-ownership/$name")).readText()
}
