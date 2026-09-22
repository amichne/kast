package io.github.amichne.kast.cli.ide

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialect
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostedSchemaKeywordTest {
    private val strictRegistry =
        SchemaRegistry.withDialect(
            Dialect.builder(ExistingIdeDocuments.schemaDialect)
                .unknownKeywordFactory { keyword, _ -> error("Unknown schema keyword: $keyword") }
                .build()
        )

    @Test
    fun `all hosted endpoint keywords have explicit validator semantics`() {
        val schema =
            requireNotNull(javaClass.getResourceAsStream("/ide-hosted/hosted-endpoint.schema.json"))
                .bufferedReader()
                .use { it.readText() }
        strictRegistry.getSchema(schema).initializeValidators()
    }

    @Test
    fun `accidental unknown keywords remain detectable`() {
        assertThrows(RuntimeException::class.java) {
            strictRegistry
                .getSchema(
                    requireNotNull(javaClass.getResourceAsStream("/unknown-hosted-keyword.schema.json"))
                        .bufferedReader()
                        .use { it.readText() }
                )
                .initializeValidators()
        }
    }
}
