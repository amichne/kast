package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationAddressCliDocument
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationFileCliDocument
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationKindCliDocument
import io.github.amichne.kast.protocol.wire.presentation.SourceRangeCliDocument
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAddressSchemaReuseTest {
    @Test
    fun `reused local address admits the canonical typed projection without changing its shape`() {
        val schema = localAddressSchema()
        LocalSchemaAssertions(schema)
            .assertDefinitionEquivalent(
                "localDeclarationAddress",
                generatedRequestSchema(LocalDeclarationAddressCliDocument.serializer()),
            )
        assertTrue(validate(schema, Json.encodeToString(address())).isEmpty())
        val deep =
            address()
                .copy(
                    range = SourceRangeCliDocument(50, 51),
                    ownerRange = SourceRangeCliDocument(0, 100),
                    lexicalOwners = (1..33).map { SourceRangeCliDocument(it, 100 - it) },
                )
        assertTrue(validate(schema, Json.encodeToString(deep)).isEmpty())
    }

    @Test
    fun `reused local address retains owner bounds lexical limits required kind and closed file variants`() {
        val schema = localAddressSchema()
        assertFalse(validate(schema, Json.encodeToString(address().copy(ownerIdentity = "unproven"))).isEmpty())
        assertFalse(
            validate(
                    schema,
                    Json.encodeToString(address().copy(lexicalOwners = List(1001) { SourceRangeCliDocument(1, 9) })),
                )
                .isEmpty()
        )
        for (name in listOf("unknown-file", "missing-kind", "additional-field")) {
            val malformed = checkNotNull(javaClass.getResource("/schemas/local-address-$name.json")).readText()
            assertFalse(validate(schema, malformed).isEmpty(), name)
        }
    }

    private fun localAddressSchema() = installedServerOutputSchema(CanonicalOperation.QUERY_RUN)

    private fun validate(schema: kotlinx.serialization.json.JsonObject, document: String) =
        CallbackSchemaFixture(schema).validator("localDeclarationAddress").validate(document, InputFormat.JSON)

    private fun address() =
        LocalDeclarationAddressCliDocument(
            LocalDeclarationFileCliDocument.Workspace("/workspace/Subject.kt"),
            LocalDeclarationKindCliDocument.FUNCTION,
            SourceRangeCliDocument(2, 5),
            "canonical-signature-sha256-v1|" + "a".repeat(64),
            SourceRangeCliDocument(0, 10),
            listOf(SourceRangeCliDocument(1, 9)),
        )
}
