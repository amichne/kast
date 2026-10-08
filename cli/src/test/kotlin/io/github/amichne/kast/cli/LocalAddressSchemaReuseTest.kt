package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationAddressCliDocument
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationFileCliDocument
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationKindCliDocument
import io.github.amichne.kast.protocol.wire.presentation.SourceRangeCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAddressSchemaReuseTest {
    @Test
    fun `reused local address admits the canonical typed projection without changing its shape`() {
        val definition = localAddressDefinition()
        assertEquals(generatedRequestSchema(LocalDeclarationAddressCliDocument.serializer()), definition)
        assertTrue(validate(definition, Json.encodeToString(address())).isEmpty())
    }

    @Test
    fun `reused local address retains owner bounds lexical limits required kind and closed file variants`() {
        val definition = localAddressDefinition()
        assertFalse(validate(definition, Json.encodeToString(address().copy(ownerIdentity = "unproven"))).isEmpty())
        assertFalse(
            validate(
                    definition,
                    Json.encodeToString(address().copy(lexicalOwners = List(33) { SourceRangeCliDocument(1, 9) })),
                )
                .isEmpty()
        )
        for (name in listOf("unknown-file", "missing-kind", "additional-field")) {
            val malformed = checkNotNull(javaClass.getResource("/schemas/local-address-$name.json")).readText()
            assertFalse(validate(definition, malformed).isEmpty(), name)
        }
    }

    private fun localAddressDefinition() =
        installedServerOutputSchema(CanonicalOperation.QUERY_RUN)
            .getValue("\$defs")
            .jsonObject
            .getValue("localDeclarationAddress")
            .jsonObject

    private fun validate(schema: kotlinx.serialization.json.JsonObject, document: String) =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema.toString())
            .validate(document, InputFormat.JSON)

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
